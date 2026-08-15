package com.firstpick.cards

import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession

internal sealed interface StubHttpAction {
    data object Offline : StubHttpAction
    data class Response(val status: Int, val body: String) : StubHttpAction
}

/** Small deterministic java.net.http fake shared by ratings tests. */
internal class ScriptedHttpClient(vararg actions: StubHttpAction) : HttpClient() {
    private val script = actions.toList()
    private var index = 0

    val requestCount: Int get() = index

    override fun <T : Any?> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        val action = synchronized(this) {
            val next = script.getOrElse(index) { script.lastOrNull() ?: StubHttpAction.Offline }
            index += 1
            next
        }
        if (action is StubHttpAction.Offline) throw IOException("offline")
        action as StubHttpAction.Response
        @Suppress("UNCHECKED_CAST")
        return StubHttpResponse(request, action.status, action.body as T)
    }

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> =
        CompletableFuture.supplyAsync { send(request, responseBodyHandler) }

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = sendAsync(request, responseBodyHandler)

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
    override fun connectTimeout(): Optional<Duration> = Optional.of(Duration.ofSeconds(1))
    override fun followRedirects(): Redirect = Redirect.NEVER
    override fun proxy(): Optional<ProxySelector> = Optional.empty()
    override fun sslContext(): SSLContext = SSLContext.getDefault()
    override fun sslParameters(): SSLParameters = SSLParameters()
    override fun authenticator(): Optional<Authenticator> = Optional.empty()
    override fun version(): Version = Version.HTTP_1_1
    override fun executor(): Optional<Executor> = Optional.empty()
}

private data class StubHttpResponse<T>(
    private val sentRequest: HttpRequest,
    private val status: Int,
    private val responseBody: T,
) : HttpResponse<T> {
    override fun statusCode(): Int = status
    override fun request(): HttpRequest = sentRequest
    override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()
    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
    override fun body(): T = responseBody
    override fun sslSession(): Optional<SSLSession> = Optional.empty()
    override fun uri(): URI = sentRequest.uri()
    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}
