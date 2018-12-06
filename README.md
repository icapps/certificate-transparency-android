# Certificate Transparency for Android

To protect our apps from man-in-the-middle attacks one of the first
things that usually springs to mind is certificate pinning. However, the
issues of certificate pinning are numerous. Firstly deciding on a
reliable set of keys to pin against is tough. Once you made that
decision if your expectations don't match reality your users suffer from
not being able to access your app or website. Smashing Magazine learnt
about this the hard way in late 2016 when they blocked users access for
up to a year because of a mismatch between the pins and the
certificates. On mobile fixing an invalid pin means pushing out a new
version of an app which can still take a while to reach every user.

So with certificate pinning falling out of favour, what should you do?
The new kid in town is **certificate transparency**.


## What is Certificate Transparency?

> Certificate Transparency helps eliminate these flaws by providing an
open framework for monitoring and auditing SSL certificates in nearly
real time. Specifically, Certificate Transparency makes it possible to
detect SSL certificates that have been mistakenly issued by a
certificate authority or maliciously acquired from an otherwise
unimpeachable certificate authority. It also makes it possible to
identify certificate authorities that have gone rogue and are
maliciously issuing certificates. https://www.certificate-transparency.org

Certificate transparency works by having a network of publicly
accessible log servers that provide cryptographic evidence when a
certificate authority issues new certificates for any domain. These log
servers can then be monitored to look out for suspicious certificates as
well as audited to prove the logs are working as expected.

These log servers help achieve the three main goals:

* Make it hard to issue certificates without the domain owners knowledge
* Provide auditing and monitoring to spot mis-issued certificates
* Protect users from mis-issued certificates

When you submit a certificate to a log server, the server responds with
a signed certificate timestamp (SCT), which is a promise that the
certificate will be added to the logs within 24 hours (the maximum merge
delay). User agents, such as web browsers and mobile apps, use this SCT
to verify the validity of a domain.

For a more detailed overview of certificate transparency, please watch
the excellent video
[The Very Best of Certificate Transparency (2011-)](https://www.facebook.com/atscaleevents/videos/1904853043121124/)
from Networking @Scale 2017.

More details about how the verification works in the library can be
found at [Android Security: Certificate Transparency](https://medium.com/@appmattus/android-security-certificate-transparency-601c18157c44)

## Getting started

For Android modules include the *android* dependency in your
build.gradle file which ensures the necessary ProGuard rules are
present:

```groovy
implementation 'com.babylon.certificatetransparency:certificatetransparency-android:<latest-version>'
```

For Java library modules include the dependency as follows:

```groovy
implementation 'com.babylon.certificatetransparency:certificatetransparency:<latest-version>'
```

### OkHttp

The library allows you to create a network interceptor for use with
OkHttp where you specify which hosts to perform certificate transparency
checks on. Wildcards are accepted but note that *.babylonhealth.com will
match any sub-domain but not "babylonhealth.com" with no subdomain.

```kotlin
val interceptor = certificateTransparencyInterceptor {
    +"*.babylonhealth.com"
}

val client = OkHttpClient.Builder().apply {
    addNetworkInterceptor(interceptor)
}.build()
```

In Java, you can create the network interceptor through
[CTInterceptorBuilder](./lib/src/main/kotlin/com/babylon/certificatetransparency/CTInterceptorBuilder.kt).

### Retrofit

With Retrofit built on top of OkHttp, configuring it for certificate
transparency is as simple as setting up an OkHttpClient as shown above
and supplying that to your Retrofit.Builder.

```kotlin
val retrofit = Retrofit.Builder()
    .baseUrl("https://babylonhealth.com")
    .addConverterFactory(GsonConverterFactory.create())
    .client(okHttpClient)
    .build()
```

### HttpURLConnection

Firstly if you are still using HttpURLConnection consider upgrading to
OkHttp. The version built into Android, naturally, is a fixed version so
you won't get any security updates or bug fixes.

To use with HttpURLConnection you wrap the original hostname verifier
before calling connect() on the connection:

```kotlin
val connection = URL("https://www.babylonhealth.com").openConnection()
if (connection is HttpsURLConnection) {
    connection.hostnameVerifier = certificateTransparencyHostnameVerifier(connection.hostnameVerifier) {
        +"*.babylonhealth.com"
    }
}
```

In Java, you can create the hostname verifier through
[CTHostnameVerifierBuilder](./lib/src/main/kotlin/com/babylon/certificatetransparency/CTHostnameVerifierBuilder.kt).

### Volley

Overriding the *HostnameVerifier* can be achieved by overriding
`createConnection` when creating the `RequestQueue`:

```kotlin

val requestQueue = Volley.newRequestQueue(applicationContext, object : HurlStack() {
    override fun createConnection(url: URL): HttpURLConnection {
        val connection = super.createConnection(url)
        if (connection is HttpsURLConnection) {
            connection.hostnameVerifier = certificateTransparencyHostnameVerifier(connection.hostnameVerifier) {
                +"*.babylonhealth.com"
            }
        }
        return connection
    }
})
```

### Apache HttpClient

Currently, there is no support in the library for Apache HttpClient.
However, adding the functionality would be relatively easy to add if
there is enough demand.

### WebViews

With WebViews on Android now being provided by Chrome, hopefully in the
long-term certificate transparency support will come for free. There is
a proposal to add an [Expect-CT](https://datatracker.ietf.org/doc/draft-stark-expect-ct/)
header to instruct user agents to expect valid SCTs which would help
enforce this.

Assuming that never happens, WebViews are tricky, not least because
there is no perfect way to implement certificate transparency in them.
The best you can do is override *shouldInterceptRequest* and implement
the network calls yourself using one of the above methods. However, you
can only intercept GET requests so if your WebViews use POST requests
then you are out of luck.

## Advanced configuration

### Network Interceptor

The network interceptor allows you to configure the following
properties:

**Trust Manager** [X509TrustManager](https://docs.oracle.com/javase/6/docs/api/javax/net/ssl/X509TrustManager.html)
used to clean the certificate chain
<br/>*Default:* Platform default [X509TrustManager](https://docs.oracle.com/javase/6/docs/api/javax/net/ssl/X509TrustManager.html)
created through [TrustManagerFactory](http://docs.oracle.com/javase/6/docs/api/javax/net/ssl/TrustManagerFactory.html)

**Log List Data Source** A [DataSource](./lib/src/main/kotlin/com/babylon/certificatetransparency/datasource/DataSource.kt)
providing a list of [LogServer](./lib/src/main/kotlin/com/babylon/certificatetransparency/loglist/LogServer.kt)
<br/>*Default:* In memory cached log list loaded from https://www.gstatic.com/ct/log_list/log_list.json

**Fail On Error** Determine if a failure to pass certificate
transparency results in the connection being closed. A value of true
ensures the connection is closed on errors
<br/>*Default:* true

**Logger** [Logger](./lib/src/main/kotlin/com/babylon/certificatetransparency/Logger.kt)
which will be called with all results.
<br/>Default: none

**Hosts** Verify certificate transparency for hosts that match a
pattern which is a lower-case host name or wildcard pattern such as
`*.example.com`.

### HostnameVerifier

In addition to all of the properties above the hostname verifier ensures
you provide a **delegate** hostname verifier which is used to first
verify the hostname before the certificate transparency checks occur.
