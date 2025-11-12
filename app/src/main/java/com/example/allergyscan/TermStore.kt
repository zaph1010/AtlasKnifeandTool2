package com.example.allergyscan.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "allergy_terms")

object TermStore {
    private val KEY_TERMS = stringSetPreferencesKey("terms")

    fun termsFlow(context: Context): Flow<Set<String>> =
        context.dataStore.data.map { prefs ->
            prefs[KEY_TERMS] ?: setOf("barley", "barley flour", "malted barley", "beer")
        }

    suspend fun saveTerms(context: Context, terms: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TERMS] = terms
        }
    }
}
