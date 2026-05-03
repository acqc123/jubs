package com.jsub.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.jsub.app.data.SettingsRepository
import com.jsub.app.model.AppSettings
import com.jsub.app.model.DisplayMode
import com.jsub.app.model.SpeechProvider
import com.jsub.app.model.TranslationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置ViewModel
 *
 * 管理设置页面的UI状态和数据持久化。
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository(application)

    private val _settings = MutableLiveData<AppSettings>()
    val settings: LiveData<AppSettings> = _settings

    private val _saveSuccess = MutableLiveData<Boolean>()
    val saveSuccess: LiveData<Boolean> = _saveSuccess

    private val _speechApiKey = MutableLiveData<String>()
    val speechApiKey: LiveData<String> = _speechApiKey

    private val _translationApiKey = MutableLiveData<String>()
    val translationApiKey: LiveData<String> = _translationApiKey

    private val _speechProvider = MutableLiveData<SpeechProvider>()
    val speechProvider: LiveData<SpeechProvider> = _speechProvider

    private val _translationProvider = MutableLiveData<TranslationProvider>()
    val translationProvider: LiveData<TranslationProvider> = _translationProvider

    private val _displayMode = MutableLiveData<DisplayMode>()
    val displayMode: LiveData<DisplayMode> = _displayMode

    private val _fontSize = MutableLiveData<Int>()
    val fontSize: LiveData<Int> = _fontSize

    private val _bgOpacity = MutableLiveData<Int>()
    val bgOpacity: LiveData<Int> = _bgOpacity

    init {
        loadSettings()
    }

    /**
     * 加载当前设置
     */
    fun loadSettings() {
        viewModelScope.launch {
            val settings = withContext(Dispatchers.IO) {
                repository.loadSettings()
            }
            _settings.value = settings
            _speechApiKey.value = settings.speechApiKey
            _translationApiKey.value = settings.translationApiKey
            _speechProvider.value = settings.speechProvider
            _translationProvider.value = settings.translationProvider
            _displayMode.value = settings.displayMode
            _fontSize.value = settings.fontSize
            _bgOpacity.value = settings.bgOpacity
        }
    }

    /**
     * 保存设置
     */
    fun saveSettings(
        speechKey: String = _speechApiKey.value ?: "",
        translationKey: String = _translationApiKey.value ?: "",
        speechProvider: SpeechProvider = _speechProvider.value ?: SpeechProvider.SENSEVOICE_LOCAL,
        mode: DisplayMode = _displayMode.value ?: DisplayMode.BILINGUAL,
        provider: TranslationProvider = _translationProvider.value ?: TranslationProvider.DEEPSEEK,
        fontSize: Int = _fontSize.value ?: 16,
        bgOpacity: Int = _bgOpacity.value ?: 80
    ) {
        viewModelScope.launch {
            val newSettings = AppSettings(
                speechApiKey = speechKey,
                translationApiKey = translationKey,
                displayMode = mode,
                fontSize = fontSize,
                bgOpacity = bgOpacity,
                speechProvider = speechProvider,
                translationProvider = provider
            )
            withContext(Dispatchers.IO) {
                repository.saveSettings(newSettings)
            }
            _settings.value = newSettings
            _saveSuccess.value = true
        }
    }

    // 设置各个字段
    fun setSpeechApiKey(key: String) {
        _speechApiKey.value = key
    }

    fun setTranslationApiKey(key: String) {
        _translationApiKey.value = key
    }

    fun setSpeechProvider(provider: SpeechProvider) {
        _speechProvider.value = provider
    }

    fun setTranslationProvider(provider: TranslationProvider) {
        _translationProvider.value = provider
    }

    fun setDisplayMode(mode: DisplayMode) {
        _displayMode.value = mode
    }

    fun setFontSize(size: Int) {
        _fontSize.value = size
    }

    fun setBgOpacity(opacity: Int) {
        _bgOpacity.value = opacity
    }

    fun resetSaveStatus() {
        _saveSuccess.value = false
    }
}
