/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.util.git.sshj

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import app.passwordstore.Application
import app.passwordstore.R
import app.passwordstore.ui.setup.readAtMost
import app.passwordstore.util.crypto.keystoreAesKey
import app.passwordstore.util.crypto.requireUserAuthentication
import app.passwordstore.util.crypto.seal
import app.passwordstore.util.crypto.unseal
import app.passwordstore.util.extensions.getEncryptedGitPrefs
import app.passwordstore.util.extensions.getString
import app.passwordstore.util.extensions.sharedPrefs
import app.passwordstore.util.extensions.unsafeLazy
import app.passwordstore.util.settings.PreferenceKeys
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.runCatching
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.asLog
import logcat.logcat
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Ed25519KeyFactory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters

private const val PROVIDER_ANDROID_KEY_STORE = "AndroidKeyStore"
private const val KEYSTORE_ALIAS = "sshkey"
private val WRAPPED_KEY_AAD = "ssh_key".encodeToByteArray()

private val androidKeystore: KeyStore by unsafeLazy {
  KeyStore.getInstance(PROVIDER_ANDROID_KEY_STORE).apply { load(null) }
}

private val KeyStore.sshPrivateKey
  get() = getKey(KEYSTORE_ALIAS, null) as? PrivateKey

private val KeyStore.sshPublicKey
  get() = getCertificate(KEYSTORE_ALIAS)?.publicKey

fun parseSshPublicKey(sshPublicKey: String): PublicKey? {
  val sshKeyParts = sshPublicKey.split("""\s+""".toRegex())
  if (sshKeyParts.size < 2) return null
  return Buffer.PlainBuffer(Base64.decode(sshKeyParts[1], Base64.NO_WRAP)).readPublicKey()
}

fun toSshPublicKey(publicKey: PublicKey): String {
  val rawPublicKey = Buffer.PlainBuffer().putPublicKey(publicKey).compactData
  val keyType = KeyType.fromKey(publicKey)
  return "$keyType ${Base64.encodeToString(rawPublicKey, Base64.NO_WRAP)}"
}

/** The Ed25519 key pair for a 32-byte RFC 8032 private key [seed]. */
internal fun ed25519KeyPair(seed: ByteArray) =
  KeyPair(
    Ed25519KeyFactory.getPublicKey(Ed25519PrivateKeyParameters(seed).generatePublicKey().encoded),
    Ed25519KeyFactory.getPrivateKey(seed),
  )

/**
 * True when [lines] look like an SSH private key sshj can load: private key markers on the first
 * and last line, in a format sshj recognizes. An OpenPGP private key block has the same markers and
 * is refused by the format check.
 */
internal fun isSshPrivateKey(lines: List<String>): Boolean {
  if (
    lines.size < 2 ||
      !Regex("BEGIN .* PRIVATE KEY").containsMatchIn(lines.first()) ||
      !Regex("END .* PRIVATE KEY").containsMatchIn(lines.last())
  )
    return false
  return try {
    KeyProviderUtil.detectKeyFileFormat(lines.joinToString("\n"), false) != KeyFormat.Unknown
  } catch (_: IOException) {
    false
  }
}

object SshKey {

  val sshPublicKey
    get() = if (publicKeyFile.exists()) publicKeyFile.readText() else null

  val canShowSshPublicKey
    get() = type in listOf(Type.LegacyGenerated, Type.KeystoreNative, Type.KeystoreWrappedEd25519)

  val exists
    get() = type != null

  val mustAuthenticate: Boolean
    get() {
      return runCatching {
          if (type !in listOf(Type.KeystoreNative, Type.KeystoreWrappedEd25519)) return false
          when (val key = androidKeystore.getKey(KEYSTORE_ALIAS, null)) {
            is PrivateKey -> {
              val factory = KeyFactory.getInstance(key.algorithm, PROVIDER_ANDROID_KEY_STORE)
              return factory.getKeySpec(key, KeyInfo::class.java).isUserAuthenticationRequired
            }
            is SecretKey -> {
              val factory = SecretKeyFactory.getInstance(key.algorithm, PROVIDER_ANDROID_KEY_STORE)
              (factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo).isUserAuthenticationRequired
            }
            else -> throw IllegalStateException("SSH key does not exist in Keystore")
          }
        }
        .getOrElse { error ->
          // It is fine to swallow the exception here since it will reappear when the key
          // is
          // used for SSH authentication and can then be shown in the UI.
          logcat { error.asLog() }
          false
        }
    }

  private val context: Context
    get() = Application.instance.applicationContext

  private val privateKeyFile
    get() = File(context.filesDir, ".ssh_key")

  private val publicKeyFile
    get() = File(context.filesDir, ".ssh_key.pub")

  private var type: Type?
    get() = Type.fromValue(context.sharedPrefs.getString(PreferenceKeys.GIT_REMOTE_KEY_TYPE))
    set(value) =
      context.sharedPrefs.edit { putString(PreferenceKeys.GIT_REMOTE_KEY_TYPE, value?.value) }

  private val isStrongBoxSupported by unsafeLazy {
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
  }

  private enum class Type(val value: String) {
    Imported("imported"),
    KeystoreNative("keystore_native"),
    KeystoreWrappedEd25519("keystore_wrapped_ed25519"),

    // Behaves like `Imported`, but allows to view the public key.
    LegacyGenerated("legacy_generated");

    companion object {

      fun fromValue(value: String?): Type? = entries.associateBy { it.value }[value]
    }
  }

  enum class Algorithm(
    val algorithm: String,
    val applyToSpec: KeyGenParameterSpec.Builder.() -> Unit,
  ) {
    Rsa(
      KeyProperties.KEY_ALGORITHM_RSA,
      {
        setKeySize(3072)
        setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
        setDigests(
          KeyProperties.DIGEST_SHA1,
          KeyProperties.DIGEST_SHA256,
          KeyProperties.DIGEST_SHA512,
        )
      },
    ),
    Ecdsa(
      KeyProperties.KEY_ALGORITHM_EC,
      {
        setKeySize(256)
        setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
        setDigests(KeyProperties.DIGEST_SHA256)
        setIsStrongBoxBacked(isStrongBoxSupported)
      },
    ),
  }

  private fun delete() {
    if (privateKeyFile.isFile) {
      privateKeyFile.delete()
    }
    deleteKeyExtras()
    type = null
  }

  /** Everything of the current key but its private key file. */
  private fun deleteKeyExtras() {
    androidKeystore.deleteEntry(KEYSTORE_ALIAS)
    if (publicKeyFile.isFile) {
      publicKeyFile.delete()
    }
    context.getEncryptedGitPrefs().edit { remove(PreferenceKeys.SSH_KEY_LOCAL_PASSPHRASE) }
  }

  fun import(uri: Uri) {
    // First check whether the content at uri is likely an SSH private key.
    val fileSize =
      context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
        cursor ->
        // A single row; none when the provider cannot tell: the IOException below.
        if (cursor.moveToFirst()) cursor.getInt(0) else null
      } ?: throw IOException(context.getString(R.string.ssh_key_does_not_exist))

    // We assume that an SSH key's ideal size is > 0 bytes && < 100 kilobytes.
    if (fileSize > 100_000 || fileSize == 0)
      throw IllegalArgumentException(
        context.getString(R.string.ssh_key_import_error_not_an_ssh_key_message)
      )

    val sshKeyInputStream =
      context.contentResolver.openInputStream(uri)
        ?: throw IOException(context.getString(R.string.ssh_key_does_not_exist))
    // Bounded whatever the provider claimed above: a larger stream is refused, not read whole.
    val bytes =
      sshKeyInputStream.use { readAtMost(it, 100_000) }
        ?: throw IllegalArgumentException(
          context.getString(R.string.ssh_key_import_error_not_an_ssh_key_message)
        )
    val lines =
      try {
        bytes.inputStream().bufferedReader().readLines()
      } finally {
        bytes.fill(0)
      }

    // Private key markers and a format sshj can read; the current key stays when this fails.
    if (!isSshPrivateKey(lines))
      throw IllegalArgumentException(
        context.getString(R.string.ssh_key_import_error_not_an_ssh_key_message)
      )

    // At this point, we are reasonably confident that we have actually been provided a private
    // key. It replaces the old one atomically (an I/O error keeps the old key), then the old key's
    // Keystore entry, public key and passphrase go.
    val temp = File(privateKeyFile.parentFile, ".${privateKeyFile.name}.tmp")
    try {
      // Canonicalize line endings to '\n'.
      temp.writeText(lines.joinToString("\n"))
      Files.move(
        temp.toPath(),
        privateKeyFile.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      temp.delete()
    }
    type = Type.Imported
    deleteKeyExtras()
  }

  /** Deletes an Ed25519 key wrapped by androidx security-crypto, which can no longer be read. */
  @Deprecated("To be used only in Migrations.kt")
  fun deleteSecurityCryptoWrappedKey() {
    if (type == Type.KeystoreWrappedEd25519) delete()
  }

  @Deprecated("To be used only in Migrations.kt")
  fun useLegacyKey(isGenerated: Boolean) {
    type = if (isGenerated) Type.LegacyGenerated else Type.Imported
  }

  suspend fun generateKeystoreWrappedEd25519Key(requireAuthentication: Boolean) =
    withContext(Dispatchers.IO) {
      delete()

      val wrappingKey =
        keystoreAesKey(KEYSTORE_ALIAS, strongBox = true) {
          if (requireAuthentication) requireUserAuthentication(15)
        }
      // Generate the ed25519 key pair and encrypt the private key.
      val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
      val keyPair = ed25519KeyPair(seed)
      privateKeyFile.writeBytes(seal(wrappingKey, seed, WRAPPED_KEY_AAD))

      // Write public key in SSH format to .ssh_key.pub.
      publicKeyFile.writeText(toSshPublicKey(keyPair.public))

      type = Type.KeystoreWrappedEd25519
    }

  fun generateKeystoreNativeKey(algorithm: Algorithm, requireAuthentication: Boolean) {
    delete()

    // Generate Keystore-backed private key.
    val parameterSpec =
      KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_SIGN).run {
        apply(algorithm.applyToSpec)
        if (requireAuthentication) requireUserAuthentication(30)
        build()
      }
    val keyPair =
      KeyPairGenerator.getInstance(algorithm.algorithm, PROVIDER_ANDROID_KEY_STORE).run {
        initialize(parameterSpec)
        generateKeyPair()
      }

    // Write public key in SSH format to .ssh_key.pub.
    publicKeyFile.writeText(toSshPublicKey(keyPair.public))

    type = Type.KeystoreNative
  }

  fun provide(client: SSHClient, passphraseFinder: InteractivePasswordFinder): KeyProvider? =
    when (type) {
      Type.LegacyGenerated,
      Type.Imported -> client.loadKeys(privateKeyFile.absolutePath, passphraseFinder)
      Type.KeystoreNative -> KeystoreNativeKeyProvider
      Type.KeystoreWrappedEd25519 -> KeystoreWrappedEd25519KeyProvider
      null -> null
    }

  private object KeystoreNativeKeyProvider : KeyProvider {

    override fun getPublic(): PublicKey =
      runCatching { androidKeystore.sshPublicKey!! }
        .getOrElse { error ->
          logcat { error.asLog() }
          throw IOException(
            "Failed to get public key '$KEYSTORE_ALIAS' from Android Keystore",
            error,
          )
        }

    override fun getPrivate(): PrivateKey =
      runCatching { androidKeystore.sshPrivateKey!! }
        .getOrElse { error ->
          logcat { error.asLog() }
          throw IOException(
            "Failed to access private key '$KEYSTORE_ALIAS' from Android Keystore",
            error,
          )
        }

    override fun getType(): KeyType = KeyType.fromKey(public)
  }

  private object KeystoreWrappedEd25519KeyProvider : KeyProvider {

    override fun getPublic(): PublicKey =
      runCatching { parseSshPublicKey(sshPublicKey!!)!! }
        .getOrElse { error ->
          logcat { error.asLog() }
          throw IOException("Failed to get the public key for wrapped ed25519 key", error)
        }

    override fun getPrivate(): PrivateKey =
      runCatching {
          val seed =
            unseal(keystoreAesKey(KEYSTORE_ALIAS), privateKeyFile.readBytes(), WRAPPED_KEY_AAD)
          Ed25519KeyFactory.getPrivateKey(seed)
        }
        .getOrElse { error ->
          logcat { error.asLog() }
          throw IOException("Failed to unwrap wrapped ed25519 key", error)
        }

    override fun getType(): KeyType = KeyType.fromKey(public)
  }
}
