package nl.giejay.android.tv.immich.api

import nl.giejay.android.tv.immich.api.interceptor.ResponseLoggingInterceptor
import nl.giejay.android.tv.immich.api.util.UnsafeOkHttpClient
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import timber.log.Timber

object ApiClientFactory {

    // Clients built from it share one connection pool and dispatcher (API calls, Glide, ...)
    // instead of each opening their own connections and threads
    private val baseClient: OkHttpClient by lazy { OkHttpClient() }

    fun getClient(disableSsl: Boolean, apiKey: String, debugMode: Boolean): OkHttpClient {
        val apiKeyInterceptor = interceptor(apiKey)
        val builder = if (disableSsl)
            UnsafeOkHttpClient.unsafeOkHttpClient()
        else baseClient.newBuilder()
        builder.addInterceptor(apiKeyInterceptor)
        return if(debugMode){
            builder.addInterceptor(ResponseLoggingInterceptor()).build()
        } else
            builder.build()
    }

    private fun interceptor(apiKey: String): Interceptor = Interceptor { chain ->
        try {
            val newRequest = chain.request().newBuilder()
                .addHeader("x-api-key", apiKey.trim())
                .build()
            chain.proceed(newRequest)
        } catch (e: Exception) {
            Timber.e(e, "Error adding API key header, invalid format")
            chain.proceed(chain.request()) // Proceed with the original request if there's an error
        }
    }
}