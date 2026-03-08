package com.kitchendisplay.app.ui.messages

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kitchendisplay.app.R
import com.kitchendisplay.app.models.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessagesAdapter(
    private val messages: List<Message>,
    private val onVoicePlay: (Message) -> Unit
) : RecyclerView.Adapter<MessagesAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm  dd/MM", Locale.getDefault())

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvHeader: TextView = itemView.findViewById(R.id.tv_message_header)
        val tvBody: TextView = itemView.findViewById(R.id.tv_message_body)
        val tvTime: TextView = itemView.findViewById(R.id.tv_message_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = messages[position]
        val direction = if (msg.direction == Message.Direction.SENT) "→" else "←"
        holder.tvHeader.text = "$direction ${msg.contactName}"
        holder.tvTime.text = timeFormat.format(Date(msg.timestamp))

        if (msg.isVoice) {
            holder.tvBody.text = "🎤 Voice message — tap to play"
            holder.tvBody.setOnClickListener { onVoicePlay(msg) }
        } else {
            holder.tvBody.text = msg.text
            holder.tvBody.setOnClickListener(null)
        }
    }

    override fun getItemCount() = messages.size
}
