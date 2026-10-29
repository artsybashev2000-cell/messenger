package com.example.btmessenger

import android.bluetooth.BluetoothDevice
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

data class DeviceRow(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val bonded: Boolean,
    val isPhone: Boolean
)

data class ChatMessage(
    val text: String,
    val fromMe: Boolean,
    val time: String,
    val geo: Pair<Double, Double>? = null
)

private val AVATAR_COLORS = intArrayOf(
    0xFFE17076.toInt(), 0xFFFAA774.toInt(), 0xFFA695E7.toInt(), 0xFF7BC862.toInt(),
    0xFF6EC9CB.toInt(), 0xFF65AADD.toInt(), 0xFFEE7AAE.toInt()
)

fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun avatarColor(seed: String): Int =
    AVATAR_COLORS[(seed.hashCode() and 0x7fffffff) % AVATAR_COLORS.size]

fun avatarLetter(name: String): String =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/** Список устройств в виде «списка чатов». Сопряжённые — как закреплённые, сверху. */
class DeviceAdapter(private val onClick: (DeviceRow) -> Unit) :
    RecyclerView.Adapter<DeviceAdapter.VH>() {

    private val all = mutableListOf<DeviceRow>()
    private val shown = mutableListOf<DeviceRow>()
    private var query = ""

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: TextView = view.findViewById(R.id.avatar)
        val name: TextView = view.findViewById(R.id.name)
        val info: TextView = view.findViewById(R.id.info)
        val pin: ImageView = view.findViewById(R.id.pin)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))

    override fun getItemCount(): Int = shown.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = shown[position]
        holder.avatar.text = avatarLetter(row.name)
        holder.avatar.background = circle(avatarColor(row.address))
        holder.name.text = row.name
        holder.info.text = if (row.bonded) "сопряжено · ${row.address}" else row.address
        holder.pin.visibility = if (row.bonded) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(row) }
    }

    private fun applyFilter() {
        val q = query.trim()
        shown.clear()
        if (q.isEmpty()) {
            shown.addAll(all)
        } else {
            shown.addAll(all.filter {
                it.name.contains(q, ignoreCase = true) || it.address.contains(q, ignoreCase = true)
            })
        }
        notifyDataSetChanged()
    }

    fun setQuery(text: String) {
        query = text
        applyFilter()
    }

    fun addIfAbsent(row: DeviceRow) {
        if (all.any { it.address == row.address }) return
        all.add(row)
        // сначала сопряжённые («закреплённые»), внутри — телефоны выше наушников и браслетов
        all.sortWith(compareByDescending<DeviceRow> { it.bonded }.thenByDescending { it.isPhone })
        applyFilter()
    }

    fun clear() {
        all.clear()
        applyFilter()
    }

    fun isEmpty(): Boolean = all.isEmpty()
}

/** Сообщения в виде пузырей: свои справа, чужие слева. */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = mutableListOf<ChatMessage>()
    var onGeoClick: ((Double, Double) -> Unit)? = null

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val bubble: LinearLayout = view.findViewById(R.id.bubble)
        val text: TextView = view.findViewById(R.id.msgText)
        val geoLink: TextView = view.findViewById(R.id.geoLink)
        val time: TextView = view.findViewById(R.id.msgTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val ctx = holder.itemView.context

        val lp = holder.bubble.layoutParams as FrameLayout.LayoutParams
        lp.gravity = if (m.fromMe) Gravity.END else Gravity.START
        holder.bubble.layoutParams = lp
        holder.bubble.setBackgroundResource(if (m.fromMe) R.drawable.bg_bubble_out else R.drawable.bg_bubble_in)

        holder.text.text = m.text
        holder.time.text = m.time
        holder.time.setTextColor(
            ContextCompat.getColor(ctx, if (m.fromMe) R.color.tg_time_out else R.color.tg_secondary)
        )
        // у своих сообщений — галочка «отправлено», как в Telegram
        holder.time.setCompoundDrawablesWithIntrinsicBounds(0, 0, if (m.fromMe) R.drawable.ic_check else 0, 0)

        val geo = m.geo
        holder.geoLink.visibility = if (geo != null) View.VISIBLE else View.GONE
        holder.bubble.setOnClickListener {
            if (geo != null) onGeoClick?.invoke(geo.first, geo.second)
        }
        holder.bubble.isClickable = geo != null
    }

    fun add(message: ChatMessage) {
        items.add(message)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun lastIndex(): Int = items.size - 1
}
