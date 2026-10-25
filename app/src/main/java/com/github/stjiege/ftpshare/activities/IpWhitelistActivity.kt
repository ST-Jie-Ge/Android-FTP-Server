package com.github.stjiege.ftpshare.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.github.stjiege.ftpshare.Constants
import com.github.stjiege.ftpshare.R
import com.github.stjiege.ftpshare.services.FtpService
import com.github.stjiege.ftpshare.utils.CommonUtils
import java.util.UUID

/**
 * Manages the IP whitelist.
 *
 * A row is created disabled and empty: it holds no address at all, so it never matches a client and
 * is inert. It becomes active only once a valid IPv4 host, an IPv4 CIDR block or
 * an IPv4 free range has been entered. There is no manual on/off switch - the only thing the user
 * can do with an active row is change its address or delete it.
 *
 * An entry is stored as "value|note|id"; the id ensures identical empty rows remain distinct.
 */
class IpWhitelistActivity : BaseActivity() {
    private lateinit var listView: ListView
    private val settings by lazy { CommonUtils.getSettingSharedPreferences(this) }
    private val entries = ArrayList<Entry>()

    /** value is "" while the row is disabled and holds no address. */
    private data class Entry(var value: String, var note: String, val id: String = UUID.randomUUID().toString()) {
        /** Without an address a row is inert: it never allows a client. */
        val isActive: Boolean get() = value.isNotBlank()
    }

    private enum class Type { SINGLE, RANGE, FREE }

    private companion object {
        /** Separates the address from the note in a persisted entry. */
        const val SEP = "|"
        /** Marks a CIDR block, e.g. "192.168.1.0/24". */
        const val CIDR_SEP = '/'
        /** Marks a free range, e.g. "192.168.1.1-192.168.1.5". */
        const val RANGE_SEP = '-'
        /** A free range may only differ in the last octet, so it spans at most one /24. */
        const val MAX_RANGE_HOSTS = 256

        fun parse(raw: String): Entry {
            val separator = raw.indexOf(SEP)
            if (separator < 0) return Entry(raw.trim(), "")
            val idSeparator = raw.indexOf(SEP, separator + 1)
            val noteEnd = if (idSeparator < 0) raw.length else idSeparator
            val id = if (idSeparator < 0) UUID.randomUUID().toString() else raw.substring(idSeparator + 1).trim()
            return Entry(raw.substring(0, separator).trim(), raw.substring(separator + 1, noteEnd).trim(), id)
        }

        fun serialize(entry: Entry): String {
            val note = entry.note.replace("|", " ").replace("\n", " ").trim()
            return "${entry.value}|$note|${entry.id}"
        }

        fun typeOf(value: String): Type = when {
            value.contains(CIDR_SEP) -> Type.RANGE
            value.contains(RANGE_SEP) -> Type.FREE
            else -> Type.SINGLE
        }

        /** Splits a free-range value into its two ends; null when the value is not a range. */
        fun rangeEnds(value: String): Pair<String, String>? {
            val dash = value.indexOf(RANGE_SEP)
            if (dash <= 0 || dash == value.length - 1) return null
            return value.substring(0, dash) to value.substring(dash + 1)
        }

        /** Hosts covered by a free range, endpoints included. */
        fun rangeHostCount(value: String): Int? {
            val ends = rangeEnds(value) ?: return null
            val from = Ipv4.octets(ends.first) ?: return null
            val to = Ipv4.octets(ends.second) ?: return null
            return Math.abs(to[3] - from[3]) + 1
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!FtpService.isFTPServiceFullyStopped()) {
            Toast.makeText(this, R.string.attention_ftp_must_be_fully_stopped, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.fragment_account)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.ip_whitelist_title)
        listView = findViewById(R.id.view_user_list)
        listView.divider = null
        refreshEntries()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_ip_whitelist, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_ip_add -> { addEntry(); true }
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refreshEntries() {
        entries.clear()
        entries.addAll(loadEntries())
        bindList()
    }

    /** Re-binds the list without reloading, so a row that is not persisted yet stays visible. */
    private fun bindList() {
        listView.adapter = IpListAdapter(entries)
        listView.setOnItemClickListener { _, _, position, _ -> showEditDialog(entries[position]) }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(entries[position]); true
        }
    }

    /** Returns stored entries. Fresh-install defaults are initialized once by MyApplication. */
    private fun loadEntries(): List<Entry> {
        val stored = settings.getStringSet(Constants.PreferenceConsts.IP_WHITELIST_ENTRIES, null)
        return stored.orEmpty().map { parse(it) }.sortedBy { it.value }
    }

    /** Persist all rows, including disabled ones; unique ids prevent StringSet collisions. */
    private fun saveEntries(candidate: List<Entry>): Boolean {
        val values = candidate.map { serialize(it) }.toSet()
        if (FtpService.updateIpWhitelistEntries(this, values)) return true
        Toast.makeText(this, R.string.attention_ftp_must_be_fully_stopped, Toast.LENGTH_SHORT).show()
        return false
    }

    /**
     * Creates a disabled row and opens it right away, because an empty row does nothing until it
     * holds an address. Cancelling still leaves the row in place so it can be filled in later.
     */
    private fun addEntry() {
        val entry = Entry("", "")
        entries.add(entry)
        bindList()
        showEditDialog(entry)
    }

    private fun showEditDialog(entry: Entry) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_ip_whitelist, null)
        val single = view.findViewById<RadioButton>(R.id.dialog_ip_type_single)
        val range = view.findViewById<RadioButton>(R.id.dialog_ip_type_range)
        val free = view.findViewById<RadioButton>(R.id.dialog_ip_type_free)
        val addressLabel = view.findViewById<TextView>(R.id.dialog_ip_address_label)
        val address = view.findViewById<EditText>(R.id.dialog_ip_address)
        val prefixRow = view.findViewById<View>(R.id.dialog_ip_prefix_row)
        val prefix = view.findViewById<EditText>(R.id.dialog_ip_prefix)
        val endRow = view.findViewById<View>(R.id.dialog_ip_end_row)
        val end = view.findViewById<EditText>(R.id.dialog_ip_end)
        val note = view.findViewById<EditText>(R.id.dialog_ip_note)
        val group = view.findViewById<RadioGroup>(R.id.dialog_ip_type_group)

        val type = if (entry.isActive) typeOf(entry.value) else Type.SINGLE
        val ends = if (type == Type.FREE) rangeEnds(entry.value) else null
        when (type) {
            Type.SINGLE -> single.isChecked = true
            Type.RANGE -> range.isChecked = true
            Type.FREE -> free.isChecked = true
        }
        val slash = entry.value.indexOf(CIDR_SEP)
        val initialAddress = when {
            type == Type.RANGE && slash > 0 -> entry.value.substring(0, slash)
            type == Type.FREE -> ends?.first.orEmpty()
            type == Type.SINGLE && entry.isActive -> entry.value
            else -> ""
        }
        address.setText(initialAddress)
        end.setText(ends?.second.orEmpty())
        prefix.setText(if (type == Type.RANGE && slash > 0) entry.value.substring(slash + 1) else "24")
        note.setText(entry.note)
        if (note.text.isNotEmpty()) note.setSelection(note.text.length)

        fun currentType(): Type = when {
            free.isChecked -> Type.FREE
            range.isChecked -> Type.RANGE
            else -> Type.SINGLE
        }

        fun applyType() {
            val selected = currentType()
            prefixRow.visibility = if (selected == Type.RANGE) View.VISIBLE else View.GONE
            endRow.visibility = if (selected == Type.FREE) View.VISIBLE else View.GONE
            addressLabel.setText(if (selected == Type.FREE) R.string.ip_whitelist_start_address
            else R.string.ip_whitelist_address)
        }
        applyType()
        group.setOnCheckedChangeListener { _, _ -> applyType() }

        val dialog = AlertDialog.Builder(this)
                .setTitle(if (entry.isActive) R.string.ip_whitelist_edit_title else R.string.ip_whitelist_add_title)
                .setView(view)
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .setNeutralButton(R.string.action_account_delete, null)
                .setPositiveButton(R.string.dialog_button_confirm, null)
                .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            confirmDelete(entry)
            dialog.dismiss()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = try {
                buildValue(currentType(), address.text.toString().trim(),
                        end.text.toString().trim(), prefix.text.toString().trim())
            } catch (error: AddressError) {
                address.error = getString(R.string.ip_whitelist_invalid_input)
                address.requestFocus()
                return@setOnClickListener
            }
            if (entries.any { it !== entry && it.value == value }) {
                Toast.makeText(this, R.string.ip_whitelist_duplicate, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val updated = Entry(value, note.text.toString().trim())
            if (!saveEntries(entries.map { if (it === entry) updated else it })) return@setOnClickListener
            refreshEntries()
            dialog.dismiss()
        }
    }

    /** Turns the dialog input into a stored value, or throws [AddressError] with a ready message. */
    private fun buildValue(type: Type, rawAddress: String, rawEnd: String, rawPrefix: String): String {
        if (type != Type.FREE) {
            if (!Ipv4.isValid(rawAddress)) throw AddressError(R.string.ip_whitelist_invalid_ipv4)
            if (type == Type.SINGLE) return rawAddress
            val length = rawPrefix.toIntOrNull()
            if (length == null || length < 1 || length > 32) throw AddressError(R.string.ip_whitelist_invalid_prefix)
            if (type == Type.RANGE && !isNetworkAddress(Ipv4.octets(rawAddress)!!, length)) {
                throw AddressError(R.string.ip_whitelist_invalid_input)
            }
            return "$rawAddress/$length"
        }
        val from = Ipv4.octets(rawAddress) ?: throw AddressError(R.string.ip_whitelist_invalid_ipv4)
        val to = Ipv4.octets(rawEnd) ?: throw AddressError(R.string.ip_whitelist_invalid_ipv4)
        // A free range stays inside one /24: only the last octet may differ.
        if (from[0] != to[0] || from[1] != to[1] || from[2] != to[2]) {
            throw AddressError(R.string.ip_whitelist_invalid_free)
        }
        val low = Math.min(from[3], to[3])
        val high = Math.max(from[3], to[3])
        if (high - low + 1 > MAX_RANGE_HOSTS) throw AddressError(R.string.ip_whitelist_invalid_free)
        val head = "${from[0]}.${from[1]}.${from[2]}"
        return "$head.$low-$head.$high"
    }

    /** Stored values can predate current validation, so display malformed entries as ineffective. */
    private fun isValidStoredValue(value: String): Boolean {
        val dash = value.indexOf(RANGE_SEP)
        if (dash >= 0) {
            if (dash == 0 || dash == value.lastIndex || value.indexOf(RANGE_SEP, dash + 1) >= 0) return false
            val from = Ipv4.octets(value.substring(0, dash).trim()) ?: return false
            val to = Ipv4.octets(value.substring(dash + 1).trim()) ?: return false
            return from[0] == to[0] && from[1] == to[1] && from[2] == to[2]
        }
        val slash = value.indexOf(CIDR_SEP)
        if (slash >= 0) {
            if (slash == 0 || slash == value.lastIndex || value.indexOf(CIDR_SEP, slash + 1) >= 0) return false
            val octets = Ipv4.octets(value.substring(0, slash).trim()) ?: return false
            val prefix = value.substring(slash + 1).trim().toIntOrNull() ?: return false
            return prefix in 1..32 && isNetworkAddress(octets, prefix)
        }
        return Ipv4.isValid(value.trim())
    }

    /** CIDR entries must identify the network itself; host bits must all be zero. */
    private fun isNetworkAddress(octets: IntArray, prefix: Int): Boolean {
        val completeOctets = prefix / 8
        val remainder = prefix % 8
        var firstHostOctet = completeOctets
        if (remainder > 0) {
            val hostMask = (1 shl (8 - remainder)) - 1
            if ((octets[completeOctets] and hostMask) != 0) return false
            firstHostOctet++
        }
        for (index in firstHostOctet..3) if (octets[index] != 0) return false
        return true
    }

    private class AddressError(val messageRes: Int) : Exception()

    private fun confirmDelete(entry: Entry) {
        val summary = when {
            !entry.isActive -> getString(R.string.ip_whitelist_disabled)
            entry.note.isBlank() -> entry.value
            else -> "${entry.note}\n${entry.value}"
        }
        val message = if (entry.isActive) {
            getString(R.string.ip_whitelist_delete_active_message, summary)
        } else {
            getString(R.string.ip_whitelist_delete_disabled_message, summary)
        }
        AlertDialog.Builder(this)
                .setMessage(message)
                .setTitle(R.string.ip_whitelist_delete_title)
                .setNegativeButton(R.string.dialog_button_cancel, null)
                .setPositiveButton(R.string.action_account_delete) { _, _ ->
                    if (!saveEntries(entries.filter { it !== entry })) return@setPositiveButton
                    refreshEntries()
                }
                .show()
    }

    /** IPv4 only: the whitelist no longer accepts hostnames or IPv6 literals. */
    private object Ipv4 {
        val VALID = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        fun isValid(raw: String): Boolean = octets(raw) != null

        /** Returns the four octets, or null when the text is not a valid dotted IPv4 address. */
        fun octets(raw: String): IntArray? {
            if (!VALID.matches(raw)) return null
            val parts = raw.split('.')
            if (parts.size != 4) return null
            val values = IntArray(4)
            for (index in 0..3) {
                val part = parts[index]
                if (part.isEmpty() || part.length > 3) return null
                val number = part.toIntOrNull() ?: return null
                if (number < 0 || number > 255) return null
                values[index] = number
            }
            return values
        }
    }

    private inner class IpListAdapter(private val items: List<Entry>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Entry = items[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@IpWhitelistActivity)
                    .inflate(R.layout.item_ip_whitelist, parent, false)
            val entry = items[position]
            val title = view.findViewById<TextView>(R.id.text_ip)
            val sub = view.findViewById<TextView>(R.id.text_ip_sub)
            val icon = view.findViewById<ImageView>(R.id.item_ip_icon)
            val dark = ContextCompat.getColor(this@IpWhitelistActivity, R.color.color_text_dark)
            val muted = ContextCompat.getColor(this@IpWhitelistActivity, R.color.color_text_secondary)
            val titled = entry.note.isNotBlank()

            if (!entry.isActive) {
                // A freshly created row: greyed out so it is obvious it does nothing yet.
                title.text = if (titled) entry.note else getString(R.string.ip_whitelist_disabled)
                title.setTextColor(muted)
                sub.text = if (titled) "${getString(R.string.ip_whitelist_disabled)} · ${getString(R.string.ip_whitelist_disabled_action)}"
                else getString(R.string.ip_whitelist_disabled_action)
                icon.alpha = 0.4f
                return view
            }

            if (!isValidStoredValue(entry.value)) {
                title.text = if (titled) entry.note else entry.value
                title.setTextColor(muted)
                sub.text = "${entry.value} · ${getString(R.string.ip_whitelist_not_effective)}"
                icon.alpha = 0.4f
                return view
            }
            val label = getString(when (typeOf(entry.value)) {
                Type.SINGLE -> R.string.ip_whitelist_type_single
                Type.RANGE -> R.string.ip_whitelist_type_range
                Type.FREE -> R.string.ip_whitelist_type_free
            })
            val hosts = rangeHostCount(entry.value)
            val detail = if (hosts == null) label
            else "$label · ${getString(R.string.ip_whitelist_address_count, hosts)}"
            title.text = if (titled) entry.note else entry.value
            title.setTextColor(dark)
            sub.text = if (titled) "${entry.value} · $detail" else detail
            icon.alpha = 1f
            return view
        }
    }
}
