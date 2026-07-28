package pro.getline.vpn.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import pro.getline.vpn.core.model.LogMessage
import pro.getline.vpn.design.databinding.AdapterLogMessageBinding
import pro.getline.vpn.design.util.layoutInflater

class LogMessageAdapter(
    private val context: Context,
    private val copy: (LogMessage) -> Unit,
) :
    RecyclerView.Adapter<LogMessageAdapter.Holder>() {
    class Holder(val binding: AdapterLogMessageBinding) : RecyclerView.ViewHolder(binding.root)

    var messages: List<LogMessage> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterLogMessageBinding
                .inflate(context.layoutInflater, parent, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = messages[position]

        holder.binding.message = current
        holder.binding.root.setOnLongClickListener {
            copy(current)

            true
        }
    }

    override fun getItemCount(): Int {
        return messages.size
    }
}