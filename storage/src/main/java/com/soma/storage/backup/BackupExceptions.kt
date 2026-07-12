package com.soma.storage.backup

sealed class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The input is not a supported, complete Soma backup. */
class BackupFormatException(message: String, cause: Throwable? = null) :
    BackupException(message, cause)

/** The passphrase is wrong or authenticated backup bytes were modified. */
class BackupAuthenticationException(cause: Throwable? = null) :
    BackupException("Backup authentication failed", cause)

/** The platform could not provide the required standard cryptographic primitives. */
class BackupCryptoException(cause: Throwable) :
    BackupException("Backup cryptography is unavailable", cause)

/** A new portable backup requires a passphrase of at least twelve characters. */
class BackupPassphraseException(message: String) : BackupException(message)
