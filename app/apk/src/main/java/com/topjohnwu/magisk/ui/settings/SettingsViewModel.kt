package com.topjohnwu.magisk.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.R as CoreR
import com.topjohnwu.magisk.core.ktx.toast
import com.topjohnwu.magisk.core.model.ColorMode
import com.topjohnwu.magisk.core.tasks.AppIdentity
import com.topjohnwu.magisk.core.utils.LocaleSetting
import com.topjohnwu.magisk.core.utils.MediaStoreUtils
import com.topjohnwu.magisk.core.utils.RootUtils
import com.topjohnwu.magisk.ui.ThemeState
import com.topjohnwu.magisk.ui.navigation.Route
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class SettingsViewModel : BaseViewModel() {

    private val _uiState = MutableStateFlow(buildInitialState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private fun buildInitialState(): SettingsUiState {
        val isDynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val selectedColorIndex = ColorMode.fromValue(Config.colorMode).toUiIndex(isDynamicColorSupported)

        val localeTags = LocaleSetting.available.tags.toList()
        val localeNames = LocaleSetting.available.names.toList()
        val selectedLocaleIndex = localeTags.indexOf(Config.locale).coerceAtLeast(0)

        val updateIndex = Config.updateChannelIndex
        val isCustomChannel = updateIndex == Config.Value.CUSTOM_CHANNEL

        return SettingsUiState(
            isZygiskSupported = Const.Version.atLeast_24_0(),
            isSuRestrictSupported = Const.Version.atLeast_30_1(),
            isMultiuserSupported = !Info.isHeadlessSystemUser,
            isMultiuserEditable = Const.USER_ID == 0,
            useLocaleManager = LocaleSetting.useLocaleManager,

            availableLanguages = localeNames,
            availableLanguageTags = localeTags,
            selectedLanguageIndex = selectedLocaleIndex,
            selectedColorModeIndex = selectedColorIndex,
            isDynamicColorSupported = isDynamicColorSupported,

            selectedUpdateChannelIndex = updateIndex,
            isCustomChannel = isCustomChannel,
            customChannelUrl = Config.customChannelUrl,
            dohEnabled = Config.doh,
            checkUpdateEnabled = Config.checkUpdate,
            downloadPath = Config.downloadDir,
            downloadPathDisplay = MediaStoreUtils.fullPath(Config.downloadDir),
            randomPackageNameEnabled = Config.randName,

            zygiskEnabled = Config.zygisk,
            isZygiskPendingReboot = Config.zygisk != Info.isZygiskEnabled,
            denyListEnabled = Config.denyList,

            selectedMultiuserModeIndex = Config.suMultiuserMode,
            selectedNamespaceModeIndex = Config.suMntNamespaceMode,
            selectedNotificationModeIndex = Config.suNotification,
            suRestrictEnabled = Config.suRestrict,
        ).withEnvironment()
    }

    // Device and environment state that can change while the app is in the background
    private fun SettingsUiState.withEnvironment(): SettingsUiState {
        val isMagiskInstalled = Info.env.isActive
        val isSuperUserAvailable = Info.showSuperUser
        return copy(
            hasRootSections = isMagiskInstalled || isSuperUserAvailable,
            isMagiskInstalled = isMagiskInstalled,
            isSuperUserAvailable = isSuperUserAvailable,
            isDeviceSecure = Info.isDeviceSecure,
            // Config.suAuth also depends on whether the device is secure
            suAuthEnabled = Config.suAuth,
            // Turned off when the notification permission is denied on startup
            checkUpdateEnabled = Config.checkUpdate,
        )
    }

    fun refreshEnvironment() {
        _uiState.update { it.withEnvironment() }
    }

    fun setColorMode(index: Int) {
        val newMode = ColorMode.fromUiIndex(index, _uiState.value.isDynamicColorSupported)
        Config.colorMode = newMode.value
        ThemeState.colorMode = newMode.value
        _uiState.update { it.copy(selectedColorModeIndex = index) }
    }

    fun setLanguage(index: Int) {
        val tag = _uiState.value.availableLanguageTags.getOrNull(index) ?: return
        Config.locale = tag
        _uiState.update { it.copy(selectedLanguageIndex = index) }
    }

    fun setUpdateChannel(index: Int) {
        Config.updateChannel = index
        Info.resetUpdate()
        val isCustom = index == Config.Value.CUSTOM_CHANNEL
        val showUrlDialog = isCustom && Config.customChannelUrl.isBlank()
        _uiState.update {
            it.copy(
                selectedUpdateChannelIndex = index,
                isCustomChannel = isCustom,
                activeDialog = if (showUrlDialog) SettingsDialog.CustomChannelUrl("") else it.activeDialog,
            )
        }
    }

    fun openCustomChannelUrlDialog() {
        _uiState.update {
            it.copy(activeDialog = SettingsDialog.CustomChannelUrl(it.customChannelUrl))
        }
    }

    fun saveCustomChannelUrl(url: String) {
        Config.customChannelUrl = url
        Info.resetUpdate()
        _uiState.update {
            it.copy(
                customChannelUrl = url,
                activeDialog = null,
            )
        }
    }

    fun setDoh(enabled: Boolean) {
        Config.doh = enabled
        _uiState.update { it.copy(dohEnabled = enabled) }
    }

    fun setCheckUpdate(enabled: Boolean) {
        Config.checkUpdate = enabled
        _uiState.update { it.copy(checkUpdateEnabled = enabled) }
    }

    fun openDownloadPathDialog() {
        _uiState.update {
            it.copy(activeDialog = SettingsDialog.DownloadPath(it.downloadPath))
        }
    }

    fun saveDownloadPath(path: String) {
        Config.downloadDir = path
        _uiState.update {
            it.copy(
                downloadPath = path,
                downloadPathDisplay = MediaStoreUtils.fullPath(path),
                activeDialog = null,
            )
        }
    }

    fun setRandomPackageName(enabled: Boolean) {
        Config.randName = enabled
        _uiState.update { it.copy(randomPackageNameEnabled = enabled) }
    }

    fun openAppIdentityDialog() {
        if (!_uiState.value.identityBusy && Const.USER_ID == 0) {
            _uiState.update { it.copy(activeDialog = SettingsDialog.AppIdentity) }
        }
    }

    fun changeAppIdentity() {
        if (_uiState.value.identityBusy || Const.USER_ID != 0) return
        _uiState.update { it.copy(identityBusy = true, activeDialog = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (AppIdentity.isHidden(AppContext)) {
                        AppIdentity.restore(AppContext)
                    } else {
                        AppIdentity.hide(AppContext)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "App identity change failed")
                showToast(CoreR.string.identity_failure)
            } finally {
                _uiState.update { it.copy(identityBusy = false) }
            }
        }
    }

    fun createHosts() {
        viewModelScope.launch {
            RootUtils.addSystemlessHosts()
            AppContext.toast(CoreR.string.settings_hosts_toast, Toast.LENGTH_SHORT)
        }
    }

    fun toggleZygisk(enabled: Boolean) {
        Config.zygisk = enabled
        val isMismatch = enabled != Info.isZygiskEnabled
        _uiState.update {
            it.copy(
                zygiskEnabled = enabled,
                isZygiskPendingReboot = isMismatch,
            )
        }
        if (isMismatch) {
            showToast(CoreR.string.reboot_apply_change)
        }
    }

    fun toggleDenyList(enabled: Boolean) {
        if (_uiState.value.denyListBusy) return
        _uiState.update { it.copy(denyListBusy = true) }
        val cmd = if (enabled) "enable" else "disable"
        Shell.cmd("magisk --denylist $cmd").submit { result ->
            if (result.isSuccess) {
                Config.denyList = enabled
                _uiState.update { it.copy(denyListEnabled = enabled, denyListBusy = false) }
            } else {
                _uiState.update { it.copy(denyListBusy = false) }
                Timber.e("DenyList update failed: %s", result.err.joinToString("\n"))
                showToast(CoreR.string.root_isolation_failure)
            }
        }
    }

    fun navigateToDenyList() {
        navigateTo(Route.DenyList)
    }

    fun setSuAuth(enabled: Boolean) {
        Config.suAuth = enabled
        _uiState.update { it.copy(suAuthEnabled = enabled) }
    }

    fun setMultiuserMode(index: Int) {
        Config.suMultiuserMode = index
        _uiState.update { it.copy(selectedMultiuserModeIndex = index) }
    }

    fun setNamespaceMode(index: Int) {
        Config.suMntNamespaceMode = index
        _uiState.update { it.copy(selectedNamespaceModeIndex = index) }
    }

    fun setNotificationMode(index: Int) {
        Config.suNotification = index
        _uiState.update { it.copy(selectedNotificationModeIndex = index) }
    }

    fun setSuRestrict(enabled: Boolean) {
        Config.suRestrict = enabled
        _uiState.update { it.copy(suRestrictEnabled = enabled) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(activeDialog = null) }
    }
}
