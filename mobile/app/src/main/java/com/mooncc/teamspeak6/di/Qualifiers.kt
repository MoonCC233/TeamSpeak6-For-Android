package com.mooncc.teamspeak6.di

import javax.inject.Qualifier

/** OkHttp client used for the screen-share signaling socket. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SignalingHttpClient

/** Application scoped coroutine scope. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
