package com.nextgentool.nudgy.di

import android.content.Context
import com.nextgentool.nudgy.auth.AuthManager
import com.nextgentool.nudgy.auth.GroupAuth
import com.nextgentool.nudgy.auth.RealGroupAuth
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Sprint 5 fix round — auth bindings (device test on ci-792: the
 * Groups "Sign in" button was dead because nothing in the rebuild
 * ever constructed an AuthManager).
 *
 * Exactly one AuthManager exists (the @Singleton): MainActivity's
 * silent sign-in at startup and the GroupsViewModel's live user +
 * sign-in flow share it, so a sign-in on one side is visible on the
 * other with no restart. v1 built AuthManager manually in
 * MainActivity (AuthManager(this)); the rebuild's MainActivity is
 * @AndroidEntryPoint, so Hilt owns the instance instead — the CALL
 * (attemptSilentSignIn(lifecycleScope) in onCreate) is the v1 one.
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    @Singleton
    fun provideAuthManager(@ApplicationContext context: Context): AuthManager =
        AuthManager(context)

    @Provides
    @Singleton
    fun provideGroupAuth(authManager: AuthManager): GroupAuth =
        RealGroupAuth(authManager)
}
