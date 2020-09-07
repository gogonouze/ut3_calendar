package com.edt.ut3.ui.preferences

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.doOnTextChanged
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import com.edt.ut3.R
import org.json.JSONArray

class PreferencesFragment: PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()
    }

    private fun setupListeners() {
        val list = findPreference<ListPreference>("theme")
        list?.run {
            setOnPreferenceChangeListener { preference, newValue ->
                val index = findIndexOfValue(newValue.toString())
                val possibleChoices = ThemePreference.values()
                val choice = possibleChoices[index.coerceAtMost(possibleChoices.lastIndex)]

                println("Setting up theme to  : $choice")
                when (choice) {
                    ThemePreference.DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    ThemePreference.LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }

                true
            }
        }

        findPreference<EditTextPreference>("section")?.let { editText ->
            editText.setOnBindEditTextListener(EditTextListener(requireContext()))
            editText.setOnPreferenceChangeListener { _, link -> setSections(link as String) }
        }

        findPreference<EditTextPreference>("dark_theme_end")?.let { editTextPreference ->
            editTextPreference.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME

                it.addTextChangedListener(TimeEditTextListener(it))
            }
        }

        findPreference<EditTextPreference>("dark_theme_start")?.let { editTextPreference ->
            editTextPreference.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME

                it.addTextChangedListener(TimeEditTextListener(it))
            }
        }
    }

    private fun setSections(link: String) : Boolean {
        val finder = Regex("&fid[\\d]+=[\\w\\d]+")
        val sections = finder.findAll(link).map { it.value.split('=').last() }

        preferenceManager.sharedPreferences.edit().putString("groups", JSONArray(sections.toList().toTypedArray()).toString()).apply()

        return true
    }

    private class EditTextListener(private var context: Context): EditTextPreference.OnBindEditTextListener {
        override fun onBindEditText(editText: EditText) {
            editText.doOnTextChanged { text, _, _, _ ->
                val valid = text?.matches(Regex(".*edt.univ-tlse3.fr/calendar2/.*(&fid[\\d]+=[\\w\\d]+)")) ?: false
                if (!valid) {
                    editText.error = context.resources.getString(R.string.not_valid_link)
                }
            }
        }
    }

    private class TimeEditTextListener(private val editText: EditText) : TextWatcher {
        var shouldHandle = true
        var previousText : String = "00:00"
        var newText : String = "00:00"
        var previousPos = 0
        var nextPos = 0

        override fun beforeTextChanged(previous: CharSequence, start: Int, added: Int, removed: Int) {
            if (!shouldHandle) return
            previousText = previous.toString()
        }

        override fun onTextChanged(current: CharSequence, start: Int, removed: Int, added: Int) {
            if (!shouldHandle) return


            when {
                removed > 0 -> {
                    println("${previousText.toMutableList()}")
                    val res = previousText.toMutableList().apply {
                        set(if (start == 2) start - 1 else start, '0')
                    }.joinToString("")

                    println(res)
                    newText = res

                    nextPos = (if (start == 2) 1 else start)
                }

                added > 0 -> {
                    if (start >= 5 || !current[start].toString().matches(Regex("\\d")) || added > 1) {
                        newText = previousText
                        return
                    }

                    if (start == 2) {
                        newText = previousText.toMutableList().apply {
                            set(start + 1, current.get(start))
                        }.joinToString("")
                        nextPos = start + 2
                        return
                    }

                    newText = previousText.toMutableList().apply {
                        set(start, current.get(start))
                    }.joinToString("")

                    nextPos = start + 1
                }
            }
        }

        override fun afterTextChanged(p0: Editable?) {
            if (!shouldHandle) {
                shouldHandle = true
                return
            }

            shouldHandle = false

            if (!matchHoursConstrains(newText)) {
                editText.setText(previousText)
                editText.setSelection(previousPos)
            } else {
                editText.setText(newText)
                editText.setSelection(nextPos)

                previousPos = nextPos
            }

        }

        private fun matchHoursConstrains(time: String): Boolean {
            println(time)
            val splitted = time.split(":")
            if (splitted.size != 2) {
                return false
            }

            val found = splitted.find { it.matches(Regex("\\d")) }
            if (found != null) {
                return false
            }

            val hour = splitted[0].toInt()
            val minutes = splitted[1].toInt()

            return hour < 24 && minutes < 60
        }

    }
}