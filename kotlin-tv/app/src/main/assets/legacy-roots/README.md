# Supplemental roots for Android 5/6

These are public CA root certificates, not server certificates or private keys.
They supplement system trust only on API 21–23. Normal chain and hostname
validation remain required; failure to initialize retains the system defaults.

Canonical PEMs were extracted from the installed certifi CA bundle and their
DER SHA-256 fingerprints checked:

- USERTrust ECC Certification Authority:
  `4ff460d54b9c86dabfbcfc5712e0400d2bed3fbc4d4fbdaa86e06adcd2a9ad7a`
  Issuer reference: https://support.sectigo.com/Com_KnowledgeDetailPage?Id=kA03l000000c4KV
- DigiCert Global Root G2:
  `cb3ccbb76031e5e0138f8dd39a23f9de47ffc35e43c1144cea27d46a5ab1cb5f`
  Issuer reference: https://knowledge.digicert.com/general-information/digicert-trusted-root-authority-certificates

The API 21 runtime initially rejected GitHub and catalog TLS chains because its
system trust store lacked these roots. No system certificate store is modified.
