package com.mooncc.teamspeak6.di

import javax.inject.Qualifier

/** OkHttp client tuned for the WebQuery interface. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class QueryHttpClient

/** OkHttp client used for the WebRTC signaling socket. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SignalingHttpClient

/** Application scoped coroutine scope. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
