package com.example.allergyscan.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Use applicationContext behind the scenes, safe across Activity lifecycles
private val Context.dataStore by preferencesDataStore(name = "allergen_ocr_prefs")

object TermStore {
    private val TERMS_KEY = stringSetPreferencesKey("terms_set")

    fun termsFlow(context: Context): Flow<Set<String>> =
        context.applicationContext.dataStore.data.map { prefs ->
            prefs[TERMS_KEY] ?: emptySet()
        }

    suspend fun saveTerms(context: Context, terms: Set<String>) {
        context.applicationContext.dataStore.edit { prefs ->
            prefs[TERMS_KEY] = terms
        }
    }
}
