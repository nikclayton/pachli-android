/*
 * Copyright (c) 2025 Pachli Association
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

package app.pachli.components.preference

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withCreated
import androidx.preference.PreferenceFragmentCompat
import app.pachli.R
import app.pachli.core.common.extensions.viewBinding
import app.pachli.core.data.model.StatusViewData
import app.pachli.core.model.AttachmentDisplayAction
import app.pachli.core.model.Poll
import app.pachli.core.model.Status
import app.pachli.core.preferences.PrefKeys
import app.pachli.core.ui.SetMarkdownContent
import app.pachli.core.ui.SetMastodonHtmlContent
import app.pachli.core.ui.StatusActionListener
import app.pachli.core.ui.extensions.InsetType
import app.pachli.core.ui.extensions.applyWindowInsets
import app.pachli.databinding.FragmentStatusDisplayPreferenceBinding
import app.pachli.settings.makePreferenceScreen
import app.pachli.settings.switchPreference
import com.bumptech.glide.Glide
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
class StatusDisplayPreferenceFragment : PreferenceFragmentCompat() {

    private val binding by viewBinding(FragmentStatusDisplayPreferenceBinding::bind)

    private val viewModel: StatusDisplayPreferenceViewModel by viewModels()

    private val l: StatusActionListener<StatusViewData> = object : StatusActionListener<StatusViewData> {
        override fun onReply(viewData: StatusViewData) {
            TODO("Not yet implemented")
        }

        override fun onReblog(viewData: StatusViewData, reblog: Boolean) {
            TODO("Not yet implemented")
        }

        override fun onFavourite(viewData: StatusViewData, favourite: Boolean) {
            TODO("Not yet implemented")
        }

        override fun onBookmark(viewData: StatusViewData, bookmark: Boolean) {
            TODO("Not yet implemented")
        }

        override fun onMore(view: View, viewData: StatusViewData) {
            TODO("Not yet implemented")
        }

        override fun onViewAttachment(view: View?, viewData: StatusViewData, attachmentIndex: Int) {
            TODO("Not yet implemented")
        }

        override fun onViewThread(status: Status) {
            TODO("Not yet implemented")
        }

        override fun onOpenReblog(status: Status) {
            TODO("Not yet implemented")
        }

        override fun onExpandedChange(viewData: StatusViewData, expanded: Boolean) {
            Timber.d("ex change: $expanded")
            viewModel.setExpanded(expanded)
        }

        override fun onAttachmentDisplayActionChange(viewData: StatusViewData, newAction: AttachmentDisplayAction) {
            TODO("Not yet implemented")
        }

        override fun onContentCollapsedChange(viewData: StatusViewData, isCollapsed: Boolean) {
            Timber.d("col change: $isCollapsed")
            viewModel.setCollapsed(isCollapsed)
        }

        override fun onVoteInPoll(viewData: StatusViewData, poll: Poll, choices: List<Int>) {
            TODO("Not yet implemented")
        }

        override fun clearContentFilter(viewData: StatusViewData) {
            TODO("Not yet implemented")
        }

        override fun onEditFilterById(pachliAccountId: Long, filterId: String) {
            TODO("Not yet implemented")
        }

        override fun onViewMedia(pachliAccountId: Long, username: String, url: String) {
            TODO("Not yet implemented")
        }

        override fun onViewTag(tag: String) {
            TODO("Not yet implemented")
        }

        override fun onViewAccount(id: String) {
            TODO("Not yet implemented")
        }

        override fun onViewUrl(url: String) {
            TODO("Not yet implemented")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val defaultView = super.onCreateView(inflater, container, savedInstanceState)

        return FragmentStatusDisplayPreferenceBinding.inflate(inflater, container, false).root.apply {
            addView(defaultView)
            applyWindowInsets(
                left = InsetType.PADDING,
                right = InsetType.PADDING,
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val glide = Glide.with(this)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.withCreated {
                launch {
                    viewModel.state.collect {
                        val setStatusContent = if (it.statusDisplayOptions.renderMarkdown) {
                            SetMarkdownContent(requireContext())
                        } else {
                            SetMastodonHtmlContent
                        }
                        Timber.d("New viewdata: isEx ${it.viewData.isExpanded}")
                        binding.statusView.setupWithStatus(
                            setStatusContent,
                            glide,
                            it.viewData,
                            l,
                            it.statusDisplayOptions,
                        )
                    }
                }
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()

        makePreferenceScreen {
            switchPreference {
                setDefaultValue(false)
                key = PrefKeys.ABSOLUTE_TIME_VIEW
                setTitle(R.string.pref_title_absolute_time)
                isSingleLineTitle = false
            }

            switchPreference {
                setDefaultValue(true)
                key = PrefKeys.SHOW_BOT_OVERLAY
                setTitle(R.string.pref_title_bot_overlay)
                isSingleLineTitle = false
                setIcon(R.drawable.ic_bot_24dp)
            }

            switchPreference {
                setDefaultValue(false)
                key = PrefKeys.ANIMATE_GIF_AVATARS
                setTitle(R.string.pref_title_animate_gif_avatars)
                isSingleLineTitle = false
            }

            switchPreference {
                setDefaultValue(false)
                key = PrefKeys.ANIMATE_CUSTOM_EMOJIS
                setTitle(R.string.pref_title_animate_custom_emojis)
                isSingleLineTitle = false
            }

            switchPreference {
                setDefaultValue(true)
                key = PrefKeys.USE_BLURHASH
                setTitle(R.string.pref_title_gradient_for_media)
                isSingleLineTitle = false
            }

            switchPreference {
                setDefaultValue(false)
                key = PrefKeys.SHOW_CARDS_IN_TIMELINES
                setTitle(R.string.pref_title_show_cards_in_timelines)
                isSingleLineTitle = false
            }
        }
    }
}
