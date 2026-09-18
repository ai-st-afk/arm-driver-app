package ru.profstroyservices.armdriver.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.driverSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "driver_settings"
)

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {

    @Provides
    @Singleton
    fun provideDriverSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.driverSettingsDataStore
}
