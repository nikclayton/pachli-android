/*
 * Copyright 2024 Pachli Association
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

package app.pachli.core.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import app.pachli.core.ui.databinding.DialogFollowOptionsBinding
import app.pachli.core.ui.databinding.ManageRelationshipButtonBinding
import java.util.Locale
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber

// TODO:
//
// - Existing code repurposes .accountFollowButton as an edit button. They'll
// need to be made separate.
// - Existing code repurposes .accountFollowButton as an "Unblock" button.
// - "..." menu (top right) has the "Hide boosts" / "Show boosts" option, see
// AccountActivity.onCreateMenu. This can be removed in favour of the button.

data class FollowOptions(
    val includeReblogs: Boolean = true,
    val notify: Boolean = false,
    val languages: List<String>? = null,
)

// TODO: This replaces AccountActivity.FollowState
sealed interface FollowState {
    data object NotFollowing : FollowState
    data class Following(val followOptions: FollowOptions) : FollowState
    data class FollowRequested(val followOptions: FollowOptions) : FollowState
}

sealed interface FollowAction {
    data object Unfollow : FollowAction
    data object CancelFollowRequest : FollowAction
    data class Follow(val followOptions: FollowOptions) : FollowAction
    data object ShowOptions : FollowAction
}

fun interface FollowActionCallback {
    operator fun invoke(action: FollowAction)
}

/**
 * Compound view that allows the user to manage their relationship with the account.
 */
class ManageRelationshipButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr, defStyleRes) {

    private val binding: ManageRelationshipButtonBinding
    var followActionCallback: FollowActionCallback? = null

    init {
        val inflater = context.getSystemService(LayoutInflater::class.java)
        binding = ManageRelationshipButtonBinding.inflate(inflater, this)
    }

    fun bind(followState: FollowState) {
        when (followState) {
            FollowState.NotFollowing -> bindNotFollowing()
            is FollowState.FollowRequested -> bindFollowRequested(followState.followOptions)
            is FollowState.Following -> bindFollowing(followState.followOptions)
        }

        binding.followOptions.setOnClickListener {
            followActionCallback?.invoke(FollowAction.ShowOptions)
        }
    }

    /**
     * Button behaviour when the user is not following the account.
     *
     * - Clicking sends a [FollowAction.Follow].
     */
    private fun bindNotFollowing() {
        binding.button.setText(R.string.action_follow)

        binding.button.setOnClickListener {
            followActionCallback?.invoke(FollowAction.Follow(FollowOptions()))
        }
    }

    /**
     * Button behaviour when the user has sent a follow request.
     *
     * - Clicking sends a [FollowAction.CancelFollowRequest].
     */
    private fun bindFollowRequested(followOptions: FollowOptions) {
        binding.button.setText(R.string.state_follow_requested)

        binding.button.setOnClickListener {
            followActionCallback?.invoke(FollowAction.CancelFollowRequest)
        }

        // Show options dialog
    }

    /**
     * Button behaviour when the user is already following the account.
     *
     * - Clicking sends a [FollowAction.Unfollow].
     */
    private fun bindFollowing(followOptions: FollowOptions) {
        binding.button.setText(R.string.action_unfollow)

        binding.button.setOnClickListener {
            followActionCallback?.invoke(FollowAction.Unfollow)
        }

        // Show options dialog
    }
}

// WIP
//
// Should be possible to turn this in to a general "SuspendableDialog" class (and family
// of classes for single choice and multi choice items)
class FollowOptionsDialog(
    private val positiveText: String,
    private val negativeText: String? = null,
    private val neutralText: String? = null,
) : DialogFragment() {
    interface DialogCallbacks {
        fun onButtonClick(which: Int)
        fun onCancel()
        fun onDismiss()
    }

    var callbacks: DialogCallbacks? = null

    private val buttonClickListener = DialogInterface.OnClickListener { _, which ->
        callbacks?.onButtonClick(which)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogFollowOptionsBinding.inflate(layoutInflater)

        val builder = AlertDialog.Builder(requireContext())
            .setView(binding.root)

        builder.setPositiveButton(positiveText, buttonClickListener)
        negativeText?.let { builder.setNegativeButton(it, buttonClickListener) }
        neutralText?.let { builder.setNeutralButton(it, buttonClickListener) }

        return builder.create()
    }

    override fun onCancel(dialog: DialogInterface) {
        callbacks?.onCancel()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        callbacks?.onDismiss()
    }

    companion object {
        private const val ARG_LOCALES = "app.pachli.ARG_LOCALES"
//        fun newInstance(locales: List<Locale>) = FollowOptionsDialog().apply {
//            arguments = bundleOf(
//                ARG_LOCALES to locales,
//            )
//        }

        suspend fun awaitButton(
            fragmentManager: FragmentManager,
            positiveText: String,
            negativeText: String? = null,
            neutralText: String? = null,
        ): Int =
            suspendCancellableCoroutine { cont ->
                // TODO: What if `cont` was passed as a constructor parameter to
                // FollowOptionsDialog? Then the need for the callbacks could be
                // removed.
                val dialog = FollowOptionsDialog(positiveText, negativeText, neutralText).apply {
                    callbacks = object : DialogCallbacks {
                        override fun onButtonClick(which: Int) = cont.resume(which) { dismiss() }

                        override fun onCancel() {
                            cont.cancel()
                        }

                        override fun onDismiss() {
                            cont.cancel()
                        }
                    }
                    cont.invokeOnCancellation { dismiss() }
                }
                if (!fragmentManager.isStateSaved) {
                    Timber.d("Showing dialog")
                    dialog.show(fragmentManager, "follow_options_dialog")
                }
            }
    }
}

class LanguageMultiSelectDialog : DialogFragment() {
    val checkedItems = mutableListOf<Boolean>()

    val callback = DialogInterface.OnMultiChoiceClickListener { _, which, isChecked ->
        checkedItems[which] = isChecked
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val locales = BundleCompat.getParcelableArrayList(requireArguments(), ARG_LOCALES, Locale::class.java).orEmpty()
        val displayLanguages = locales.map {
            it?.displayLanguage ?: "All languages" // getString(R.string.search_operator_language_dialog_all)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Choose languages")
//            .awaitMultiChoiceItems(displayLanguages, booleanArrayOf(), android.R.string.ok)
            .create()

        return dialog
    }

    companion object {
        private const val ARG_LOCALES = "app.pachli.ARG_LOCALES"
        fun newInstance(locales: List<Locale>) = LanguageMultiSelectDialog().apply {
            arguments = bundleOf(
                ARG_LOCALES to locales,
            )
        }
    }
}
