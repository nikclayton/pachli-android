/*
 * Copyright 2025 Pachli Association
 *
 * This file is a part of Pachli.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation; either version 3 of the
 * License, or (at your option) any later version.
 *
 * Pachli is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Pachli; if not,
 * see <http://www.gnu.org/licenses>.
 */

package app.pachli.feature.accountrouter

import android.content.Intent
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pachli.core.common.PachliError
import app.pachli.core.data.repository.AccountManager
import app.pachli.core.data.repository.Loadable
import app.pachli.core.data.repository.RefreshAccountError
import app.pachli.core.data.repository.SetActiveAccountError
import app.pachli.core.database.model.AccountEntity
import app.pachli.core.navigation.AccountRouterActivityIntent.Payload
import app.pachli.core.navigation.ComposeActivityIntent
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.mapEither
import com.github.michaelbull.result.onSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal sealed interface UiState {
    data object Loading : UiState

    data object ShowLogin : UiState

    data class ChooseAccount(
        val onChooseAccount: (Long) -> Unit,
    ) : UiState

//    data class Success(
//        val pachliAccountId: Long,
//        val payload: MainActivityIntent.Payload,
//    ) : UiState

    data class FromQuickTile(val pachliAccountId: Long) : UiState

    data class FromNotificationCompose(
        val pachliAccountId: Long,
        val notificationId: Int,
        val notificationTag: String?,
        val composeOptions: ComposeActivityIntent.ComposeOptions?,
    ) : UiState

    data class FromOpenDrafts(val pachliAccountId: Long) : UiState

    data class FromRedirect(val pachliAccountId: Long, val url: String) : UiState

    data class FromShortcut(val pachliAccountId: Long) : UiState

    data class FromFollowRequest(
        val pachliAccountId: Long,
        val notificationId: Int,
        val notificationTag: String?,
    ) : UiState

    data class FromMainActivity(
        val pachliAccountId: Long,
        val showNotificationTab: Boolean = false,
    ) : UiState

    data class FromSharedData(
        val pachliAccountId: Long,
        val intent: Intent,
    ) : UiState
}

/** Actions the user can take from the UI. */
internal sealed interface UiAction

internal sealed interface FallibleUiAction : UiAction {
    data class SetActiveAccount(
        val pachliAccountId: Long,
        val payload: Payload.MainActivity,
    ) : FallibleUiAction

    data class RefreshAccount(
        val accountEntity: AccountEntity,
        val payload: Payload.MainActivity,
    ) : FallibleUiAction
}

/** Actions that succeeded. */
internal sealed interface UiSuccess {
    val action: FallibleUiAction

    /** @see [FallibleUiAction.VerifyAndAddAccount]. */
//    data class VerifyAndAddAccount(
//        override val action: FallibleUiAction.VerifyAndAddAccount,
//        val accountId: Long,
//    ) : UiSuccess

//    data class RefreshAccount(val payload: AccountRouterIntent.Payload) : UiSuccess
    data class SetActiveAccount(
        override val action: FallibleUiAction.SetActiveAccount,
        val accountEntity: AccountEntity,
    ) : UiSuccess

    data class RefreshAccount(override val action: FallibleUiAction.RefreshAccount) : UiSuccess
}

/** Actions that failed. */
internal sealed class UiError(
    @StringRes override val resourceId: Int,
    open val action: UiAction,
    override val cause: PachliError,
    override val formatArgs: Array<out String>? = null,
) : PachliError {
    data class SetActiveAccount(
        override val action: FallibleUiAction.SetActiveAccount,
        override val cause: SetActiveAccountError,
    ) : UiError(R.string.main_viewmodel_error_set_active_account, action, cause)

    data class RefreshAccount(
        override val action: FallibleUiAction.RefreshAccount,
        override val cause: RefreshAccountError,
    ) : UiError(R.string.main_viewmodel_error_refresh_account, action, cause)
}

@HiltViewModel
internal class AccountRouterViewModel @Inject constructor(
    private val accountManager: AccountManager,
) : ViewModel() {
    val accounts = accountManager.pachliAccountsFlow.map { Loadable.Loaded(it) }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        Loadable.Loading,
    )

    private val uiAction = MutableSharedFlow<UiAction>()
    val accept: (UiAction) -> Unit = { action -> viewModelScope.launch { uiAction.emit(action) } }

    private val _uiResult = Channel<Result<UiSuccess, UiError>>()
    val uiResult = _uiResult.receiveAsFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState = _uiState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UiState.Loading,
    )

    init {
        viewModelScope.launch { uiAction.collect { launch { onUiAction(it) } } }
    }

    /** Processes actions received from the UI and updates [_uiState]. */
    private suspend fun onUiAction(uiAction: UiAction) {
        val result = when (uiAction) {
            // is FallibleUiAction.VerifyAndAddAccount -> verifyAndAddAccount(uiAction)
            is FallibleUiAction.RefreshAccount -> onRefreshAccount(uiAction)
            is FallibleUiAction.SetActiveAccount -> onSetActiveAccount(uiAction)
        }

        _uiResult.send(result)
    }

    /**
     * Parses the payload from [intent] and ensures the relevant account is refreshed.
     */
//    private fun onParsePayload(intent: Intent, savedInstanceState: Bundle?) {
//        Timber.d("Parsing payload")
//        Timber.d("  intent: $intent")
//        Timber.d("  savedInstanceState: $savedInstanceState")
//
//        val pachliAccountId = accountManager.resolvePachliAccountId(intent.pachliAccountId)
//        Timber.d("  pachliAccountId: ${intent.pachliAccountId}")
//        Timber.d("  pachliAccountId (resolved): $pachliAccountId")
//
//        // If the account can't be determined then either ask the user to login, or show
//        if (pachliAccountId == null) {
//            _uiState.value = UiState.ShowLogin
//            return
//        }
//
//        if (savedInstanceState != null) {
//            Timber.d("Have savedInstanceState")
//            accept(
//                FallibleUiAction.RefreshAccount(
//                    intent.pachliAccountId,
//                    UiState.FromMainActivity(intent.pachliAccountId),
//                ),
//            )
//            return
//        }
//
//        val payload = AccountRouterIntent.payload(intent)
//        Timber.d("  payload: $payload")
//
//        when (payload) {
//            AccountRouterIntent.Payload.QuickTile -> {
//                // TODO: Handle the case where there's a single account, no need to show account
//                // chooser.
//                _uiState.value = UiState.ChooseAccount(
//                    onChooseAccount = { accept(FallibleUiAction.RefreshAccount(it, UiState.FromQuickTile(it))) },
//                )
//            }
//            // Don't switch account
//            is AccountRouterIntent.Payload.NotificationCompose -> accept(
//                FallibleUiAction.RefreshAccount(
//                    pachliAccountId,
//                    UiState.FromNotificationCompose(
//                        pachliAccountId,
//                        payload.notificationId,
//                        payload.notificationTag,
//                        payload.composeOptions,
//                    ),
//                ),
//            )
//
//            // Existing code doesn't switch account -- suspect that's a bug.
//            is AccountRouterIntent.Payload.Notification -> when (payload.notificationType) {
//                Notification.Type.FOLLOW_REQUEST -> {
//                    accept(
//                        FallibleUiAction.RefreshAccount(
//                            pachliAccountId,
//                            UiState.FromFollowRequest(
//                                pachliAccountId,
//                                payload.notificationId,
//                                payload.notificationTag,
//                            ),
//                        ),
//                    )
//                }
//
//                else -> {
//                    accept(
//                        FallibleUiAction.RefreshAccount(
//                            pachliAccountId,
//                            UiState.FromMainActivity(
//                                pachliAccountId,
//                                showNotificationTab = true,
//                            ),
//                        ),
//                    )
//                }
//            }
//            // Switch account
//            AccountRouterIntent.Payload.OpenDrafts -> {
//                accept(
//                    FallibleUiAction.RefreshAccount(
//                        pachliAccountId,
//                        UiState.FromOpenDrafts(pachliAccountId),
//                        makeActive = true,
//                    ),
//                )
//            }
//            // ?
//            is AccountRouterIntent.Payload.Redirect ->
//                accept(
//                    FallibleUiAction.RefreshAccount(
//                        pachliAccountId,
//                        UiState.FromRedirect(
//                            pachliAccountId,
//                            payload.url,
//                        ),
//                    ),
//                )
//            // Don't switch account
//            AccountRouterIntent.Payload.Shortcut -> {
//                // accept(FallibleUiAction.RefreshAccount(it, successState = UiState.FromShortcut(pachliAccountId)))
//            }
//
//            AccountRouterIntent.Payload.SwitchAccount -> TODO()
//            null -> {
//                if (canHandleMimeType(intent.type)) {
//                    // Payload should be a type that means "There is content to share"
//                    _uiState.value = UiState.ChooseAccount(
//                        onChooseAccount = {
//                            accept(
//                                FallibleUiAction.RefreshAccount(
//                                    pachliAccountId = it,
//                                    UiState.FromSharedData(pachliAccountId, intent),
//                                ),
//                            )
//                        },
//                    )
//                } else {
//                    // Payload should be a type that means "Launch MainActivity as normal"
//                    accept(
//                        FallibleUiAction.RefreshAccount(
//                            pachliAccountId = pachliAccountId,
//                            UiState.FromMainActivity(pachliAccountId),
//                        ),
//                    )
//                }
//            }
//        }
//    }

    private suspend fun onSetActiveAccount(action: FallibleUiAction.SetActiveAccount): Result<UiSuccess.SetActiveAccount, UiError.SetActiveAccount> {
        return accountManager.setActiveAccount(action.pachliAccountId)
            .mapEither(
                { UiSuccess.SetActiveAccount(action, it) },
                { UiError.SetActiveAccount(action, it) },
            )
            .onSuccess {
//                pachliAccountIdFlow.value = it.accountEntity.id
//                uiAction.emit(FallibleUiAction.RefreshAccount(it.accountEntity))
            }
    }

    private suspend fun onRefreshAccount(action: FallibleUiAction.RefreshAccount): Result<UiSuccess.RefreshAccount, UiError.RefreshAccount> {
        return accountManager.refresh(action.accountEntity.id)
            .mapEither(
                { UiSuccess.RefreshAccount(action) },
                { UiError.RefreshAccount(action, it) },
            )
    }

    companion object {
        // TODO: Copied from ComposeActivity, can be deleted from there
        fun canHandleMimeType(mimeType: String?): Boolean {
            return mimeType != null && (mimeType.startsWith("image/") || mimeType.startsWith("video/") || mimeType.startsWith("audio/") || mimeType == "text/plain")
        }
    }
}
