/*
 * Copyright 2020 Tusky Contributors
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

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import androidx.core.graphics.withSave
import app.pachli.core.network.model.Emoji
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import java.lang.ref.WeakReference
import java.util.regex.Pattern

/**
 * replaces emoji shortcodes in a text with EmojiSpans
 * @receiver the text containing custom emojis
 * @param emojis a list of the custom emojis (nullable for backward compatibility with old mastodon instances)
 * @param view a reference to the a view the emojis will be shown in (should be the TextView, but parents of the TextView are also acceptable)
 * @return the text with the shortcodes replaced by EmojiSpans
*/
fun CharSequence.emojify(emojis: List<Emoji>?, view: View, animate: Boolean): Spanned {
    if (emojis.isNullOrEmpty()) {
        return SpannableStringBuilder.valueOf(this)
    }

    val builder = SpannableStringBuilder.valueOf(this)

    // TODO: This is O(len(emojis)) x O(len(input)). Would be better to parse the string
    // once, looking up each emoji shortcode as it's found (and accept a
    // Map<shortcode: String, (url: String, staticUrl: String)>
    emojis.forEach { (shortcode, url, staticUrl) ->
        val matcher = Pattern.compile(":$shortcode:", Pattern.LITERAL)
            .matcher(this)

        while (matcher.find()) {
            val span = EmojiSpan(WeakReference(view))

            builder.setSpan(span, matcher.start(), matcher.end(), 0)
            Glide.with(view)
                .asDrawable()
                .load(
                    if (animate) {
                        url
                    } else {
                        staticUrl
                    },
                )
                .into(span.getTarget(animate))
        }
    }
    return builder
}

class EmojiSpan(val viewWeakReference: WeakReference<View>) : ReplacementSpan() {
    var imageDrawable: Drawable? = null

    /** Scale the emoji up/down from the calculated size */
    var scaleFactor = 1.0f

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            /* update FontMetricsInt or otherwise span does not get drawn when
             * it covers the whole text */
            val metrics = paint.fontMetricsInt
            fm.top = metrics.top
            fm.ascent = metrics.ascent
            fm.descent = metrics.descent
            fm.bottom = metrics.bottom
        }

        return (paint.textSize * 1.2 * scaleFactor).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        imageDrawable?.let { drawable ->
            canvas.withSave {
                // start with a width relative to the text size
                var emojiWidth = paint.textSize * 1.1

                // calculate the height, keeping the aspect ratio correct
                val drawableWidth = drawable.intrinsicWidth
                val drawableHeight = drawable.intrinsicHeight
                var emojiHeight = emojiWidth / drawableWidth * drawableHeight

                // how much vertical space there is draw the emoji
                val drawableSpace = (bottom - top).toDouble()

                // in case the calculated height is bigger than the available space, scale the emoji down, preserving aspect ratio
                if (emojiHeight > drawableSpace) {
                    emojiWidth *= drawableSpace / emojiHeight
                    emojiHeight = drawableSpace
                }
                emojiHeight *= scaleFactor
                emojiWidth *= scaleFactor

                drawable.setBounds(0, 0, emojiWidth.toInt(), emojiHeight.toInt())

                // vertically center the emoji in the line
                val transY = top + (drawableSpace / 2 - emojiHeight / 2)

                translate(x, transY.toFloat())
                drawable.draw(this)
            }
        }
    }

    fun getTarget(animate: Boolean): Target<Drawable> {
        return object : CustomTarget<Drawable>() {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                viewWeakReference.get()?.let { view ->
                    if (animate && resource is Animatable) {
                        val callback = resource.callback

                        resource.callback = object : Drawable.Callback {
                            override fun unscheduleDrawable(p0: Drawable, p1: Runnable) {
                                callback?.unscheduleDrawable(p0, p1)
                            }
                            override fun scheduleDrawable(p0: Drawable, p1: Runnable, p2: Long) {
                                callback?.scheduleDrawable(p0, p1, p2)
                            }
                            override fun invalidateDrawable(p0: Drawable) {
                                callback?.invalidateDrawable(p0)
                                view.invalidate()
                            }
                        }
                        resource.start()
                    }

                    imageDrawable = resource
                    view.invalidate()
                }
            }

            override fun onLoadCleared(placeholder: Drawable?) {}
        }
    }
}
