package com.zmlworks.kanjicolorkey.keepass

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.getEntries
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyEntry
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.database.modifiers.removeEntry
import app.keemobile.kotpass.cryptography.format.CipherProvider
import app.keemobile.kotpass.cryptography.format.TwofishCipher
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.UUID

/**
 * Thin wrapper around kotpass for the emergency-key flows. All secrets stay in memory;
 * callers should wipe returned composite keys when done.
 */
object KeePassFacade {

    /** AES + ChaCha20 + Twofish (for Keepass2Android Offline read). */
    @JvmField
    val ALL_CIPHERS: List<CipherProvider> = BaseCiphers.entries + TwofishCipher

    /** Default Argon2id for new DBs: 64 MiB, 3 iterations, parallelism 2 (mobile-friendly). */
    const val DEFAULT_ARGON2_MEMORY = 64L * 1024L * 1024L
    const val DEFAULT_ARGON2_ITERATIONS = 3L
    const val DEFAULT_ARGON2_PARALLELISM = 2

    data class EntrySummary(
        val uuid: String,
        val groupPath: String,
        val title: String,
        val userName: String,
        val url: String,
        val notes: String,
        val password: String
    )

    data class OpenResult(
        val database: KeePassDatabase,
        val compositeKey: ByteArray,
        val cipherName: String,
        val kdfName: String,
        val readOnly: Boolean
    )

    /** NFC-normalize then UTF-8. */
    @JvmStatic
    fun normalizePassword(password: String): String =
        Normalizer.normalize(password, Normalizer.Form.NFC)

    @JvmStatic
    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * KeePass keyfile rules: XML v1/v2, 32-byte raw, 64-char hex, else SHA-256(file).
     */
    @JvmStatic
    fun keyfileHash(keyfileBytes: ByteArray): ByteArray {
        // Try XML via Credentials.from key path by constructing credentials and reading...
        // Reuse kotpass parse: Credentials.from(keyData) stores parsed key as EncryptedValue.
        val creds = Credentials.from(keyfileBytes)
        return creds.key!!.getBinary()
    }

    /**
     * Pre-KDF composite key: SHA-256( SHA-256(utf8_nfc(password)) || keyfileHash? ).
     * Password may be empty string (empty component still hashed) — pass null to omit password.
     */
    @JvmStatic
    fun compositeKey(password: String?, keyfileBytes: ByteArray?): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        if (password != null) {
            val pw = normalizePassword(password).toByteArray(StandardCharsets.UTF_8)
            md.update(sha256(pw))
            pw.fill(0)
        }
        if (keyfileBytes != null) {
            md.update(keyfileHash(keyfileBytes))
        }
        return md.digest()
    }

    @JvmStatic
    fun credentialsFromPassword(password: String, keyfileBytes: ByteArray?): Credentials {
        val nfc = normalizePassword(password)
        val enc = EncryptedValue.fromString(nfc)
        return if (keyfileBytes != null) Credentials.from(enc, keyfileBytes)
        else Credentials.from(enc)
    }

    @JvmStatic
    fun credentialsFromComposite(composite: ByteArray): Credentials =
        Credentials.fromComposite(composite)

    @JvmStatic
    fun open(input: InputStream, credentials: Credentials): OpenResult {
        val bytes = input.readBytes()
        return openBytes(bytes, credentials)
    }

    @JvmStatic
    fun openBytes(bytes: ByteArray, credentials: Credentials): OpenResult {
        val db = KeePassDatabase.decode(
            ByteArrayInputStream(bytes),
            credentials,
            cipherProviders = ALL_CIPHERS
        )
        val composite = when {
            credentials.isCompositeKey -> credentials.passphrase!!.getBinary().copyOf()
            else -> {
                // Recompute from stored credential components for emergency-key export
                val parts = mutableListOf<ByteArray>()
                credentials.passphrase?.getBinary()?.let { parts.add(it) }
                credentials.key?.getBinary()?.let { parts.add(it) }
                val joined = parts.fold(ByteArray(0)) { a, b -> a + b }
                sha256(joined).also { joined.fill(0); parts.forEach { it.fill(0) } }
            }
        }
        val cipher = when (db.header.cipherId) {
            BaseCiphers.Aes.uuid -> "AES-256"
            BaseCiphers.ChaCha20.uuid -> "ChaCha20"
            TwofishCipher.uuid -> "Twofish"
            else -> db.header.cipherId.toString()
        }
        val kdf = when (val h = db.header) {
            is app.keemobile.kotpass.database.header.DatabaseHeader.Ver3x ->
                "AES-KDF (${h.transformRounds} rounds)"
            is app.keemobile.kotpass.database.header.DatabaseHeader.Ver4x -> when (val k = h.kdfParameters) {
                is KdfParameters.Aes -> "AES-KDF (${k.rounds} rounds)"
                is KdfParameters.Argon2 ->
                    "${k.variant.name} I=${k.iterations} M=${k.memory / 1024u / 1024u}MiB P=${k.parallelism}"
            }
        }
        val readOnly = db.header.cipherId == TwofishCipher.uuid
        return OpenResult(db, composite, cipher, kdf, readOnly)
    }

    /** Verify password opens the file; return composite key (caller wipes). */
    @JvmStatic
    fun makeEmergencyKey(dbBytes: ByteArray, password: String, keyfileBytes: ByteArray?): ByteArray {
        val creds = credentialsFromPassword(password, keyfileBytes)
        val expected = compositeKey(password, keyfileBytes)
        val opened = openBytes(dbBytes, creds)
        // Also verify composite path
        openBytes(dbBytes, credentialsFromComposite(expected))
        val out = expected
        // wipe opened copy
        opened.compositeKey.fill(0)
        return out
    }

    @JvmStatic
    fun listEntries(db: KeePassDatabase): List<EntrySummary> {
        val out = ArrayList<EntrySummary>()
        db.getEntries { true }.forEach { (group, entries) ->
            val path = group.name
            entries.forEach { e ->
                out.add(
                    EntrySummary(
                        uuid = e.uuid.toString(),
                        groupPath = path,
                        title = e.fields.title?.content.orEmpty(),
                        userName = e.fields.userName?.content.orEmpty(),
                        url = e.fields.url?.content.orEmpty(),
                        notes = e.fields.notes?.content.orEmpty(),
                        password = e.fields.password?.content.orEmpty()
                    )
                )
            }
        }
        return out
    }

    enum class CipherChoice { AES, CHACHA20 }

    @JvmStatic
    @JvmOverloads
    fun createDatabase(
        rootName: String,
        password: String,
        keyfileBytes: ByteArray?,
        cipher: CipherChoice,
        argonMemory: Long = DEFAULT_ARGON2_MEMORY,
        argonIterations: Long = DEFAULT_ARGON2_ITERATIONS,
        argonParallelism: Int = DEFAULT_ARGON2_PARALLELISM
    ): OpenResult {
        val creds = credentialsFromPassword(password, keyfileBytes)
        val random = SecureRandom()
        var db = KeePassDatabase.Ver4x.create(
            rootName = rootName,
            meta = Meta(generator = "kanji-color-key", name = rootName),
            credentials = creds,
            random = random
        )
        val cipherId = when (cipher) {
            CipherChoice.AES -> BaseCiphers.Aes.uuid
            CipherChoice.CHACHA20 -> BaseCiphers.ChaCha20.uuid
        }
        val ivLen = when (cipher) {
            CipherChoice.AES -> BaseCiphers.Aes.ivLength.toInt()
            CipherChoice.CHACHA20 -> BaseCiphers.ChaCha20.ivLength.toInt()
        }
        val salt = randomBytes(random, 32)
        val kdf = KdfParameters.Argon2(
            variant = KdfParameters.Argon2.Variant.Argon2id,
            salt = salt,
            parallelism = argonParallelism.toUInt(),
            memory = argonMemory.toULong(),
            iterations = argonIterations.toULong(),
            version = 0x13u,
            secretKey = null,
            associatedData = null
        )
        db = db.copy(
            header = db.header.copy(
                cipherId = cipherId,
                encryptionIV = randomBytes(random, ivLen),
                kdfParameters = kdf
            )
        )
        // Encode to bytes and reopen to validate
        val encoded = encodeToBytes(db)
        return openBytes(encoded, creds)
    }

    @JvmStatic
    fun encodeToBytes(db: KeePassDatabase): ByteArray {
        val bos = ByteArrayOutputStream()
        db.encode(bos, cipherProviders = ALL_CIPHERS)
        return bos.toByteArray()
    }

    @JvmStatic
    fun encodeTo(db: KeePassDatabase, out: OutputStream) {
        db.encode(out, cipherProviders = ALL_CIPHERS)
    }

    private fun randomBytes(random: SecureRandom, n: Int): ByteString =
        ByteArray(n).also { random.nextBytes(it) }.toByteString()

    /**
     * Encode, reopen with same credentials to verify, then return bytes.
     * Safety check before overwriting a user document.
     */
    @JvmStatic
    fun encodeVerified(db: KeePassDatabase): ByteArray {
        val bytes = encodeToBytes(db)
        openBytes(bytes, db.credentials) // throws if bad
        return bytes
    }

    @JvmStatic
    fun addEntry(
        db: KeePassDatabase,
        title: String,
        userName: String,
        password: String,
        url: String,
        notes: String
    ): KeePassDatabase {
        val fields = EntryFields.createDefault() + mapOf(
            BasicField.Title() to EntryValue.Plain(title),
            BasicField.UserName() to EntryValue.Plain(userName),
            BasicField.Password() to EntryValue.Encrypted(EncryptedValue.fromString(password)),
            BasicField.Url() to EntryValue.Plain(url),
            BasicField.Notes() to EntryValue.Plain(notes)
        )
        val entry = Entry(uuid = UUID.randomUUID(), fields = fields)
        return db.modifyParentGroup {
            copy(entries = entries + entry)
        }
    }

    @JvmStatic
    fun updateEntry(
        db: KeePassDatabase,
        uuid: String,
        title: String,
        userName: String,
        password: String,
        url: String,
        notes: String
    ): KeePassDatabase {
        val id = UUID.fromString(uuid)
        return db.modifyEntry(id) {
            copy(
                fields = fields + mapOf(
                    BasicField.Title() to EntryValue.Plain(title),
                    BasicField.UserName() to EntryValue.Plain(userName),
                    BasicField.Password() to EntryValue.Encrypted(EncryptedValue.fromString(password)),
                    BasicField.Url() to EntryValue.Plain(url),
                    BasicField.Notes() to EntryValue.Plain(notes)
                )
            )
        }
    }

    @JvmStatic
    fun deleteEntry(db: KeePassDatabase, uuid: String): KeePassDatabase =
        db.removeEntry(UUID.fromString(uuid))

    @JvmStatic
    fun isTwofish(db: KeePassDatabase): Boolean =
        db.header.cipherId == TwofishCipher.uuid

    @JvmStatic
    fun wipe(bytes: ByteArray?) {
        bytes?.fill(0)
    }

    @JvmStatic
    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    @JvmStatic
    fun hexToBytes(hex: String): ByteArray {
        val s = hex.replace(Regex("\\s+"), "").lowercase()
        require(s.length == 64) { "Need 64 hex chars (256-bit key)" }
        return ByteArray(32) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
