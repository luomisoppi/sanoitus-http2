package sanoitus.http2.utils

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

import sun.security.x509._

// Thanks, Mike! (https://bfo.com/blog/2011/03/08/odds_and_ends_creating_a_new_x_509_certificate/)
object CertificateCreator {
  val keyPairGenerator = KeyPairGenerator.getInstance("RSA")

  def create(days: Int = 365): (PrivateKey, X509Certificate) = {
    val pair = keyPairGenerator.genKeyPair()
    val privkey = pair.getPrivate()
    val dn = "CN=localhost"
    val algorithm = "SHA256withRSA"

    val info = new X509CertInfo()
    val from = new Date()
    val to = new Date(from.getTime() + days * 86400000L)
    val interval = new CertificateValidity(from, to)
    val sn = new BigInteger(64, new SecureRandom())
    val owner = new X500Name(dn)

    info.set(X509CertInfo.VALIDITY, interval)
    info.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(sn))
    info.set(X509CertInfo.SUBJECT, owner)
    info.set(X509CertInfo.ISSUER, owner)
    info.set(X509CertInfo.KEY, new CertificateX509Key(pair.getPublic()))
    info.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3))
    var algo = new AlgorithmId(AlgorithmId.md5WithRSAEncryption_oid)
    info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algo))

    var cert = new X509CertImpl(info)
    cert.sign(privkey, algorithm)

    algo = cert.get(X509CertImpl.SIG_ALG).asInstanceOf[AlgorithmId]
    info.set(CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM, algo)
    cert = new X509CertImpl(info)
    cert.sign(privkey, algorithm)
    (pair.getPrivate, cert)
  }
}
