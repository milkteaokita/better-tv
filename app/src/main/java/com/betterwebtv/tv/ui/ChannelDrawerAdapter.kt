package com.betterwebtv.tv.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.betterwebtv.tv.databinding.ItemChannelBinding
import com.betterwebtv.tv.model.Channel
import com.betterwebtv.tv.model.ChannelJsonCodec

class ChannelDrawerAdapter(
    context: Context,
    private val onChannelClicked: (Channel) -> Unit,
    private val onChannelFocused: (Int) -> Unit
) : RecyclerView.Adapter<ChannelDrawerAdapter.ChannelViewHolder>() {

    private val imageLoader: ImageLoader = ImageLoader.Builder(context.applicationContext)
        .components {
            add(SvgDecoder.Factory())
        }
        .crossfade(true)
        .build()

    private var channels: List<Channel> = emptyList()
    private var currentChannelId: String? = null

    fun submitList(newChannels: List<Channel>, playingChannelId: String?) {
        channels = newChannels
        currentChannelId = playingChannelId
        notifyDataSetChanged()
    }

    fun setCurrentChannelId(playingChannelId: String?) {
        if (currentChannelId == playingChannelId) return
        currentChannelId = playingChannelId
        notifyDataSetChanged()
    }

    fun getChannels(): List<Channel> = channels

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = channels[position]
        holder.bind(channel, channel.id == currentChannelId, position)
    }

    override fun getItemCount(): Int = channels.size

    fun bindLogoOrBadge(
        logoView: ImageView,
        badgeView: TextView,
        channel: Channel
    ) {
        val badgeColorInt = parseColorSafe(
            channel.badgeColor.ifBlank { ChannelJsonCodec.pickBadgeColor(channel.number, channel.name) }
        )
        val badgeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = logoView.resources.displayMetrics.density * 8f
            setColor(badgeColorInt)
        }
        badgeView.background = badgeBg
        badgeView.text = shortLabel(channel.name)
        badgeView.visibility = View.VISIBLE

        if (channel.logoUrl.isBlank()) {
            logoView.setImageDrawable(null)
            logoView.visibility = View.GONE
            return
        }

        logoView.visibility = View.INVISIBLE
        val request = ImageRequest.Builder(logoView.context)
            .data(channel.logoUrl)
            .target(
                onSuccess = { drawable ->
                    logoView.setImageDrawable(drawable)
                    logoView.visibility = View.VISIBLE
                    badgeView.visibility = View.GONE
                },
                onError = {
                    logoView.setImageDrawable(null)
                    logoView.visibility = View.GONE
                    badgeView.visibility = View.VISIBLE
                }
            )
            .build()
        imageLoader.enqueue(request)
    }

    inner class ChannelViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(channel: Channel, isPlaying: Boolean, position: Int) {
            binding.itemChannelNumber.text = channel.number.toString()
            binding.itemChannelName.text = channel.name
            binding.itemLiveIndicator.visibility = if (isPlaying) View.VISIBLE else View.GONE
            binding.root.isSelected = isPlaying

            bindLogoOrBadge(binding.itemLogoImage, binding.itemBadgeText, channel)

            binding.root.setOnClickListener {
                onChannelClicked(channel)
            }
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                val targetScale = if (hasFocus) 1.025f else 1.0f
                view.animate().scaleX(targetScale).scaleY(targetScale).setDuration(110).start()
                if (hasFocus) {
                    val pos = bindingAdapterPosition
                    onChannelFocused(if (pos != RecyclerView.NO_POSITION) pos else position)
                }
            }
        }
    }

    companion object {
        private fun shortLabel(name: String): String {
            val trimmed = name.trim()
            return if (trimmed.length <= 4) trimmed else trimmed.take(4)
        }

        private fun parseColorSafe(hex: String): Int {
            return try {
                Color.parseColor(hex)
            } catch (_: Exception) {
                Color.parseColor("#1D4ED8")
            }
        }
    }
}
