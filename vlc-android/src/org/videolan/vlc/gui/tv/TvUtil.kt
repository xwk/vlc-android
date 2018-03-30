/*****************************************************************************
 * TvUtil.java
 *
 * Copyright © 2014-2017 VLC authors, VideoLAN and VideoLabs
 * Author: Geoffrey Métais
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 */
package org.videolan.vlc.gui.tv

import android.animation.ValueAnimator
import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.support.annotation.RequiresApi
import android.support.v17.leanback.app.BackgroundManager
import android.support.v17.leanback.widget.DiffCallback
import android.support.v17.leanback.widget.ListRow
import android.support.v17.leanback.widget.Row
import android.support.v4.content.ContextCompat
import android.support.v4.util.LruCache
import android.text.TextUtils
import android.util.Log
import android.view.View
import org.videolan.medialibrary.media.DummyItem
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.medialibrary.media.MediaWrapper
import org.videolan.vlc.R
import org.videolan.vlc.VLCApplication
import org.videolan.vlc.gui.DialogActivity
import org.videolan.vlc.gui.helpers.AudioUtil
import org.videolan.vlc.gui.helpers.BitmapUtil
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.tv.audioplayer.AudioPlayerActivity
import org.videolan.vlc.gui.tv.browser.VerticalGridActivity
import org.videolan.vlc.media.MediaUtils
import org.videolan.vlc.util.AndroidDevices
import org.videolan.vlc.util.Constants
import org.videolan.vlc.util.Constants.*
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.*
import kotlin.collections.ArrayList

@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
object TvUtil {

    private val TAG = "VLC/TvUtil"

    private val refCache = LruCache<BackgroundManager, WeakReference<ValueAnimator>>(5)

    var diffCallback: DiffCallback<MediaLibraryItem> = object : DiffCallback<MediaLibraryItem>() {
        override fun areItemsTheSame(oldItem: MediaLibraryItem, newItem: MediaLibraryItem): Boolean {
            return oldItem.equals(newItem)
        }

        override fun areContentsTheSame(oldItem: MediaLibraryItem, newItem: MediaLibraryItem): Boolean {
            if (oldItem.itemType == MediaLibraryItem.TYPE_DUMMY) return TextUtils.equals(oldItem.description, newItem.description)
            if (oldItem.itemType != MediaLibraryItem.TYPE_MEDIA) return true
            val oldMedia = oldItem as MediaWrapper
            val newMedia = newItem as MediaWrapper
            return oldMedia === newMedia || (oldMedia.time == newMedia.time
                    && TextUtils.equals(oldMedia.artworkMrl, newMedia.artworkMrl)
                    && oldMedia.seen == newMedia.seen)
        }

        override fun getChangePayload(oldItem: MediaLibraryItem, newItem: MediaLibraryItem): Any {
            if (oldItem.itemType == MediaLibraryItem.TYPE_DUMMY) return Constants.UPDATE_DESCRIPTION
            val oldMedia = oldItem as MediaWrapper
            val newMedia = newItem as MediaWrapper
            if (oldMedia.time != newMedia.time) return Constants.UPDATE_TIME
            return if (!TextUtils.equals(oldMedia.artworkMrl, newMedia.artworkMrl))
                Constants.UPDATE_THUMB
            else
                Constants.UPDATE_SEEN
        }
    }

    var listDiffCallback: DiffCallback<ListRow> = object : DiffCallback<ListRow>() {
        override fun areItemsTheSame(oldItem: ListRow, newItem: ListRow): Boolean {
            return oldItem.contentDescription == newItem.contentDescription
        }

        override fun areContentsTheSame(oldItem: ListRow, newItem: ListRow): Boolean {
            return true
        }
    }

    fun applyOverscanMargin(activity: Activity) {
        val hm = activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_horizontal)
        val vm = activity.resources.getDimensionPixelSize(R.dimen.tv_overscan_vertical)
        activity.findViewById<View>(android.R.id.content).setPadding(hm, vm, hm, vm)
    }

    fun playMedia(activity: Activity, media: MediaWrapper) {
        if (media.type == MediaWrapper.TYPE_AUDIO) {
            val tracks = ArrayList<MediaWrapper>()
            tracks.add(media)
            val intent = Intent(activity, AudioPlayerActivity::class.java)
            intent.putExtra(AudioPlayerActivity.MEDIA_LIST, tracks)
            activity.startActivity(intent)
        } else
            MediaUtils.openMedia(activity, media)
    }

    fun openMedia(activity: Activity, item: Any?, row: Row?) {
        if (item is MediaWrapper) {
            when {
                item.type == MediaWrapper.TYPE_AUDIO -> showMediaDetail(activity, item)
                item.type == MediaWrapper.TYPE_DIR -> {
                    Log.d(TAG, "openMedia: " + item.uri)
                    val intent = Intent(activity, VerticalGridActivity::class.java)
                    intent.putExtra(MainTvActivity.BROWSER_TYPE, if ("file" == item.uri.scheme) Constants.HEADER_DIRECTORIES else Constants.HEADER_NETWORK)
                    intent.data = item.uri
                    activity.startActivity(intent)
                }
                item.type == MediaWrapper.TYPE_GROUP -> {
                    val intent = Intent(activity, VerticalGridActivity::class.java)
                    intent.putExtra(MainTvActivity.BROWSER_TYPE, Constants.HEADER_VIDEO)
                    val title = item.title.substring(if (item.title.toLowerCase().startsWith("the")) 4 else 0)
                    intent.putExtra(Constants.KEY_GROUP, title)
                    activity.startActivity(intent)
                }
                else -> MediaUtils.openMedia(activity, item)
            }
        } else if (item is DummyItem) {
            if (item.id == Constants.HEADER_STREAM) {
                activity.startActivity(Intent(activity, DialogActivity::class.java).setAction(DialogActivity.KEY_STREAM)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                val intent = Intent(activity, VerticalGridActivity::class.java)
                intent.putExtra(MainTvActivity.BROWSER_TYPE, item.id)
                activity.startActivity(intent)
            }
        }
    }

    fun showMediaDetail(activity: Context, mediaWrapper: MediaWrapper) {
        val intent = Intent(activity, DetailsActivity::class.java)
        intent.putExtra("media", mediaWrapper)
        intent.putExtra("item", MediaItemDetails(mediaWrapper.title, mediaWrapper.artist, mediaWrapper.album, mediaWrapper.location, mediaWrapper.artworkURL))
        activity.startActivity(intent)
    }

    fun browseFolder(activity: Activity, type: Long, uri: Uri) {
        val intent = Intent(activity, VerticalGridActivity::class.java)
        intent.putExtra(MainTvActivity.BROWSER_TYPE, type)
        intent.data = uri
        activity.startActivity(intent)
    }

    fun playAudioList(activity: Activity, array: Array<MediaWrapper>, position: Int) {
        playAudioList(activity, ArrayList(Arrays.asList(*array)), position)
    }

    fun playAudioList(activity: Activity, list: ArrayList<MediaWrapper>, position: Int) {
        val intent = Intent(activity, AudioPlayerActivity::class.java)
        intent.putExtra(AudioPlayerActivity.MEDIA_LIST, list)
        intent.putExtra(AudioPlayerActivity.MEDIA_POSITION, position)
        activity.startActivity(intent)
    }

    fun openAudioCategory(context: Activity, mediaLibraryItem: MediaLibraryItem) {
        if (mediaLibraryItem.itemType == MediaLibraryItem.TYPE_ALBUM) {
            TvUtil.playAudioList(context, mediaLibraryItem.tracks, 0)
        } else if (mediaLibraryItem.itemType == MediaLibraryItem.TYPE_MEDIA) {
            val list = ArrayList<MediaWrapper>().apply { add(mediaLibraryItem as MediaWrapper) }
            playAudioList(context, list, 0)
        } else {
            val intent = Intent(context, VerticalGridActivity::class.java)
            intent.putExtra(AUDIO_ITEM, mediaLibraryItem)
            intent.putExtra(AUDIO_CATEGORY, CATEGORY_ALBUMS)
            intent.putExtra(MainTvActivity.BROWSER_TYPE, Constants.HEADER_CATEGORIES)
            context.startActivity(intent)
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    fun updateBackground(bm: BackgroundManager?, item: Any?) {
        if (bm === null || item === null) return
        if (item is MediaLibraryItem) {
            val crop = item.itemType != MediaLibraryItem.TYPE_MEDIA || (item as MediaWrapper).type == MediaWrapper.TYPE_AUDIO
            val artworkMrl = item.artworkMrl
            if (!TextUtils.isEmpty(artworkMrl)) {
                VLCApplication.runBackground(Runnable {
                    if (bm == null) return@Runnable
                    var cover: Bitmap? = AudioUtil.readCoverBitmap(Uri.decode(artworkMrl), 512)
                            ?: return@Runnable
                    if (crop)
                        cover = BitmapUtil.centerCrop(cover, cover!!.width, cover.width * 10 / 16)
                    val blurred = UiTools.blurBitmap(cover, 10f)
                    VLCApplication.runOnMainThread(Runnable {
                        if (bm == null) return@Runnable
                        bm.color = 0
                        bm.drawable = BitmapDrawable(VLCApplication.getAppResources(), blurred)
                    })
                })
                return
            }
        }
        clearBackground(bm)
    }

    private fun clearBackground(bm: BackgroundManager) {
        bm.color = ContextCompat.getColor(VLCApplication.getAppContext(), R.color.tv_bg)
        bm.drawable = null
    }

    //See https://issuetracker.google.com/issues/37135111
    fun releaseBackgroundManager(backgroundManager: BackgroundManager) {
        val field: Field
        val ref = refCache.get(backgroundManager)
        var valueAnimator: ValueAnimator? = null
        if (ref != null) {
            valueAnimator = ref.get()
            if (valueAnimator == null) refCache.remove(backgroundManager)
        }
        if (valueAnimator == null) {
            try {
                field = backgroundManager.javaClass.getDeclaredField("mAnimator")
                field.isAccessible = true
                valueAnimator = field.get(backgroundManager) as ValueAnimator
                refCache.put(backgroundManager, WeakReference(valueAnimator))
            } catch (ignored: Exception) {
            }

        }
        if (valueAnimator != null && valueAnimator.isStarted) valueAnimator.cancel()
        backgroundManager.release()
    }

    fun getIconRes(mediaLibraryItem: MediaLibraryItem): Int {
        when (mediaLibraryItem.itemType) {
            MediaLibraryItem.TYPE_ALBUM -> return R.drawable.ic_album_big
            MediaLibraryItem.TYPE_ARTIST -> return R.drawable.ic_artist_big
            MediaLibraryItem.TYPE_GENRE -> return R.drawable.ic_genre_big
            MediaLibraryItem.TYPE_MEDIA -> {
                val mw = mediaLibraryItem as MediaWrapper
                if (mw.type == MediaWrapper.TYPE_VIDEO)
                    return R.drawable.ic_browser_video_big_normal
                return if (mw.type == MediaWrapper.TYPE_DIR && TextUtils.equals(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY, mw.location))
                    R.drawable.ic_menu_folder_big
                else
                    R.drawable.ic_song_big
            }
            MediaLibraryItem.TYPE_DUMMY -> {
                val id = mediaLibraryItem.id
                if (id == Constants.HEADER_VIDEO) {
                    return R.drawable.ic_video_collection_big
                } else if (id == Constants.HEADER_DIRECTORIES) {
                    return R.drawable.ic_menu_folder_big
                } else if (id == Constants.HEADER_NETWORK) {
                    return R.drawable.ic_menu_network_big
                } else if (id == Constants.HEADER_STREAM) {
                    return R.drawable.ic_menu_stream_big
                } else if (id == Constants.ID_SETTINGS) {
                    return R.drawable.ic_menu_preferences_big
                } else if (id == Constants.ID_ABOUT_TV || id == Constants.ID_LICENCE) {
                    return R.drawable.ic_default_cone
                } else if (id == Constants.CATEGORY_ARTISTS) {
                    return R.drawable.ic_artist_big
                } else if (id == Constants.CATEGORY_ALBUMS) {
                    return R.drawable.ic_album_big
                } else if (id == Constants.CATEGORY_GENRES) {
                    return R.drawable.ic_genre_big
                } else if (id == Constants.CATEGORY_SONGS) {
                    return R.drawable.ic_song_big
                }
                return R.drawable.ic_browser_unknown_big_normal
            }
            else -> return R.drawable.ic_browser_unknown_big_normal
        }
    }
}