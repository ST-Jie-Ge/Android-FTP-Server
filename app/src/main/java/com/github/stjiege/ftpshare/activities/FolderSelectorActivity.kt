package com.github.stjiege.ftpshare.activities

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.widget.AdapterView.OnItemClickListener
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import com.github.stjiege.ftpshare.R
import com.github.stjiege.ftpshare.utils.CommonUtils
import com.github.stjiege.ftpshare.utils.AppTaskExecutors
import com.github.stjiege.ftpshare.utils.LogManager
import com.github.stjiege.ftpshare.utils.StorageUtil
import java.io.File
import java.util.*

/**
 * 访问路径选择页。账户页与匿名页共用这一个 Activity。
 *
 * <p>界面的三块是有意为之的<b>整体</b>而不是三个圆角卡片：顶部一条 header（存储切换 +
 * 面包屑）、中间整块列表、底部操作栏。早先是「裸 Spinner + 一行绝对路径 + ListView」，
 * 操作全挂在 ActionBar 三个没有文案的图标上——「取消」实际是返回上级，「确定」选的是
 * 当前目录，用户看不出来。</p>
 *
 * <p><b>面包屑为什么值得单独做</b>：绝对路径 <code>/storage/emulated/0/Download</code>
 * 对大多数人只是一串斜杠，看不出自己在第几层、回退要点几次。拆成可点的层级后，
 * 「我在哪」和「怎么回去」都是一次点击的事。</p>
 *
 * <p><code>EXTRA_RESTRICT_TO_STORAGE_ROOT</code> 打开时，面包屑不会显示存储根以上的层级，
 * 返回上级也会停在根上——根本身可以选，<code>/storage</code> 或
 * <code>/storage/emulated</code> 不能成为账户目录。</p>
 */
open class FolderSelectorActivity : BaseActivity() {

    companion object {
        @JvmStatic
        val EXTRA_CURRENT_PATH: String = "current_path"

        @JvmStatic
        val EXTRA_SELECTED_PATH: String = "selected_path"

        /** Prevent account-directory selection from navigating above an internal/TF storage root. */
        @JvmStatic
        val EXTRA_RESTRICT_TO_STORAGE_ROOT: String = "restrict_to_storage_root"
    }

    private var file: File? = null
    private val list: ArrayList<File> = ArrayList()
    private val storagePaths: ArrayList<String> = ArrayList()
    private var segmentHost: LinearLayout? = null
    private var crumbHost: LinearLayout? = null
    private var listView: ListView? = null
    private var skeleton: View? = null
    private var emptyState: View? = null
    private var adapter: FileListAdapter? = null
    private val positionRecords = Bundle()
    private val handler = Handler(Looper.getMainLooper())
    private var restrictToStorageRoot = false

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        installBackHandler()
        setContentView(R.layout.layout_dialog_folder_selector)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_toolbar_home)
        supportActionBar?.title = resources?.getString(R.string.account_path_dialog_title)
        segmentHost = findViewById(R.id.folder_segment)
        crumbHost = findViewById(R.id.folder_crumbs)
        listView = findViewById(R.id.folder_list)
        skeleton = findViewById(R.id.folder_skeleton)
        emptyState = findViewById(R.id.folder_empty)
        val path = intent.getStringExtra(EXTRA_CURRENT_PATH)
        restrictToStorageRoot = intent.getBooleanExtra(EXTRA_RESTRICT_TO_STORAGE_ROOT, false)
        if (path == null) {
            Toast.makeText(this, "invalid selected path", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        file = try {
            File(path)
        } catch (e: Exception) {
            LogManager.warning("解析初始目录失败: ${e.javaClass.simpleName}")
            File(StorageUtil.getMainStoragePath())
        }
        try {
            val storages: List<String> = StorageUtil.getAvailableStoragePaths(this)
            storagePaths.clear()
            storagePaths.addAll(storages)
            if (restrictToStorageRoot && storagePaths.isNotEmpty()
                    && storagePaths.none { containsPath(file, it) }) {
                file = File(storagePaths.first())
            }
        } catch (e: java.lang.Exception) {
            LogManager.warning("初始化存储目录列表失败: ${e.javaClass.simpleName}")
        }
        buildSegments()

        adapter = FileListAdapter()
        listView?.setAdapter(adapter)

        findViewById<View>(R.id.folder_action_select).setOnClickListener { confirmSelection() }
        findViewById<View>(R.id.folder_action_new).setOnClickListener { showCreateFolderDialog() }

        refreshPath(file!!)
        listView?.setOnItemClickListener(OnItemClickListener { _, _, position, _ ->
            if (file != null) positionRecords.putInt(file!!.absolutePath.lowercase(Locale.US), listView?.getFirstVisiblePosition()
                    ?: 0)
            if (position == 0) {
                backtoParent()
                return@OnItemClickListener
            }
            refreshPath(list[position - 1])
        })
    }

    /** 是否把 [current] 当作 [storage] 范围内（含 storage 自身）。 */
    private fun containsPath(current: File?, storage: String): Boolean {
        val root = File(storage)
        return CommonUtils.isChildPathOfCertainPath(current, root) || filesEqual(current, root)
    }

    private fun dp(value: Float): Int =
            (value * resources.displayMetrics.density + 0.5f).toInt()

    /** 存储切换的分段按钮：只有一个存储时自然只显示一项。 */
    private fun buildSegments() {
        val host = segmentHost ?: return
        host.removeAllViews()
        if (storagePaths.isEmpty()) {
            host.visibility = View.GONE
            return
        }
        host.visibility = View.VISIBLE
        for (path in storagePaths) {
            val item = TextView(this)
            item.text = StorageUtil.getStorageDisplayName(this, path)
            item.gravity = Gravity.CENTER
            item.textSize = 12.5f
            item.setPadding(dp(4f), 0, dp(4f), 0)
            item.setBackgroundResource(R.drawable.bg_folder_segment)
            val params = LinearLayout.LayoutParams(0, dp(34f), 1f)
            if (host.childCount > 0) params.marginStart = dp(6f)
            item.layoutParams = params
            item.setOnClickListener { refreshPath(File(path)) }
            host.addView(item)
        }
        highlightSegments()
    }

    /** 选中态只改外观，不重建 View —— 重建会让点击时的 ripple 断掉。 */
    private fun highlightSegments() {
        val host = segmentHost ?: return
        val current = file
        for (i in 0 until host.childCount) {
            val item = host.getChildAt(i) as? TextView ?: continue
            val path = storagePaths.getOrNull(i)
            val selected = path != null && containsPath(current, path)
            item.isSelected = selected
            item.setTextColor(resources.getColor(
                    if (selected) R.color.colorPrimaryDark else R.color.color_text_secondary))
            item.setTypeface(item.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /**
     * 面包屑：从所属存储根开始，一级一个可点的 chip，末级高亮表示"这就是将选中的目录"。
     */
    private fun buildCrumbs() {
        val host = crumbHost ?: return
        host.removeAllViews()
        val current = file ?: return
        val trail = ArrayList<Pair<String, File>>()
        var root: File? = null
        for (path in storagePaths) {
            if (containsPath(current, path)) {
                root = File(path)
                break
            }
        }
        if (root == null) {
            // 不在任何已知存储下（restrict 打开时不会发生）：至少让用户看到自己在哪。
            trail.add(Pair(displayNameFor(current), current))
        } else {
            trail.add(Pair(StorageUtil.getStorageDisplayName(this, root.absolutePath), root))
            val chain = ArrayList<File>()
            var cursor: File? = current
            var guard = 0
            while (cursor != null && !filesEqual(cursor, root) && guard++ < 64) {
                chain.add(cursor)
                cursor = cursor.parentFile
            }
            chain.reverse()
            for (step in chain) trail.add(Pair(displayNameFor(step), step))
        }
        trail.forEachIndexed { index, entry ->
            val label = entry.first
            val target = entry.second
            if (index > 0) {
                val separator = TextView(this)
                separator.text = "›"
                separator.textSize = 11f
                separator.setTextColor(resources.getColor(R.color.color_chevron))
                separator.setPadding(dp(2f), 0, dp(2f), 0)
                host.addView(separator)
            }
            val chip = TextView(this)
            chip.text = label
            chip.textSize = 12f
            chip.setPadding(dp(7f), dp(4f), dp(7f), dp(4f))
            chip.setBackgroundResource(R.drawable.bg_folder_crumb)
            val isCurrent = index == trail.size - 1
            chip.isSelected = isCurrent
            chip.setTextColor(resources.getColor(
                    if (isCurrent) R.color.colorPrimaryDark else R.color.color_text_secondary))
            if (isCurrent) chip.setTypeface(chip.typeface, Typeface.BOLD)
            chip.setOnClickListener { refreshPath(target) }
            host.addView(chip)
        }
    }

    private fun displayNameFor(target: File): String {
        val name = target.name
        return if (name.isNullOrEmpty()) target.absolutePath else name
    }

    /** 主按钮上直接写目录名，让"确定"选的是哪个目录不再需要猜。 */
    private fun updateSelectButton() {
        val current = file ?: return
        val label = displayNameFor(current)
        val text = findViewById<TextView>(R.id.folder_action_select_text) ?: return
        text.text = if (label.isBlank()) {
            getString(R.string.folder_select_here)
        } else {
            getString(R.string.folder_select_named, label)
        }
    }

    private fun backtoParent() {
        try {
            val parent_file = file!!.parentFile
            if (parent_file == null) {
                if (!restrictToStorageRoot) finish()
                return
            }
            if (restrictToStorageRoot) {
                val currentRoot = storagePaths.firstOrNull {
                    containsPath(file, it)
                }?.let { File(it) }
                // The root itself is selectable, but its parent (/storage or /storage/emulated)
                // must never become a selectable account directory.
                if (currentRoot == null || filesEqual(file, currentRoot)
                        || !CommonUtils.isChildPathOfCertainPath(parent_file, currentRoot)) return
            }
            refreshPath(parent_file)
        } catch (e: java.lang.Exception) {
            LogManager.warning("返回上级目录失败: ${e.javaClass.simpleName}")
        }
    }

    private fun filesEqual(first: File?, second: File?): Boolean = try {
        first?.canonicalFile == second?.canonicalFile
    } catch (_: Exception) {
        first?.absolutePath == second?.absolutePath
    }

    private fun confirmSelection() {
        Intent().apply {
            putExtra(EXTRA_SELECTED_PATH, file?.absolutePath ?: "")
            setResult(RESULT_OK, this)
            finish()
        }
    }

    private fun showCreateFolderDialog() {
        AlertDialog.Builder(this)
                .setTitle(resources.getString(R.string.word_new_folder))
                .setView(LayoutInflater.from(this).inflate(R.layout.layout_with_edittext, null))
                .setPositiveButton(resources.getString(R.string.dialog_button_confirm), null)
                .setNegativeButton(resources.getString(R.string.dialog_button_cancel)) { _, _ -> }
                .show().apply {
                    findViewById<EditText>(R.id.dialog_edittext)?.setText(resources.getString(R.string.word_new_folder))
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = findViewById<EditText>(R.id.dialog_edittext)?.text.toString()
                        if (name.isNullOrBlank() || !CommonUtils.isALegalFileName(name)) {
                            Toast.makeText(this@FolderSelectorActivity, resources.getString(R.string.dialog_rename_error), Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        try {
                            val created = file?.let { File(it, name).mkdirs() } ?: false
                            if (!created) {
                                Toast.makeText(this@FolderSelectorActivity, "无法创建目录，请检查权限或存储状态", Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                        } catch (e: Exception) {
                            LogManager.warning("创建目录失败: ${e.javaClass.simpleName}")
                            Toast.makeText(this@FolderSelectorActivity, e.toString(), Toast.LENGTH_SHORT).show()
                        }
                        file?.let {
                            refreshPath(it)
                        }
                        cancel()
                    }
                }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            setResult(RESULT_CANCELED)
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** 返回键改走 AndroidX 的 OnBackPressedDispatcher：原先重写 onBackPressed() 且不调 super，
     *  在 Android 13+ 的预测式返回手势下会被当成"事件没人处理"（预览动画缺失、极端情况不退出）。
     *  执行体仍是 backtoParent()，行为等价。 */
    private fun installBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { backtoParent() }
        })
    }

    /** Monotonic id of the latest path request; results belonging to an older one are dropped. */
    private var scanRequestId = 0

    private fun refreshPath(file: File) {
        this.file = file
        scanRequestId++
        val requestId = scanRequestId
        val requested = file
        list.clear()
        adapter?.notifyDataSetChanged()
        buildCrumbs()
        highlightSegments()
        updateSelectButton()
        showState(State.LOADING)
        val accepted = AppTaskExecutors.executeDirectoryScan {
            val fileArrayList = ArrayList<File>()
            var unreadable = false
            try {
                // listFiles() 返回 null 就是"读不了"（权限、卸载中的卷、Android 10 的
                // scoped storage 都会这样）。用 orEmpty() 会把它和"空目录"混为一谈，
                // 界面也就没法告诉用户到底发生了什么。
                val files = requested.listFiles()
                if (files == null) {
                    unreadable = true
                    LogManager.warning("目录不可读取 path=${requested.absolutePath}")
                } else {
                    var hidden = 0
                    for (currentFile in files) {
                        if (!currentFile.isDirectory) continue
                        // Dot-directories are never selectable account roots, and listing them only
                        // buries the real folders the user is looking for.
                        if (currentFile.isHidden) {
                            hidden++
                            continue
                        }
                        fileArrayList.add(currentFile)
                    }
                    if (hidden > 0) {
                        LogManager.debug("目录扫描已跳过 $hidden 个隐藏目录 path=${requested.absolutePath}")
                    }
                    Collections.sort(fileArrayList)
                }
            } catch (e: java.lang.Exception) {
                unreadable = true
                LogManager.warning("读取目录失败: ${e.javaClass.simpleName}")
            } finally {
                val failed = unreadable
                handler.post {
                    // The user already navigated elsewhere: this result is stale and must not
                    // overwrite the list, otherwise the header and the content disagree.
                    if (requestId != scanRequestId) return@post
                    list.clear()
                    list.addAll(fileArrayList)
                    adapter?.notifyDataSetChanged()
                    showState(if (list.isEmpty()) {
                        if (failed) State.UNREADABLE else State.EMPTY
                    } else {
                        State.LIST
                    })
                    listView?.setSelection(positionRecords.getInt(requested.absolutePath.lowercase(Locale.US)))
                }
            }
        }
        if (!accepted) showState(if (list.isEmpty()) State.UNREADABLE else State.LIST)
    }

    private enum class State { LOADING, LIST, EMPTY, UNREADABLE }

    private fun showState(state: State) {
        val showList = state == State.LIST
        listView?.visibility = if (showList) View.VISIBLE else View.GONE
        skeleton?.visibility = if (state == State.LOADING) View.VISIBLE else View.GONE
        val showEmpty = state == State.EMPTY || state == State.UNREADABLE
        emptyState?.visibility = if (showEmpty) View.VISIBLE else View.GONE
        if (showEmpty) {
            val unreadable = state == State.UNREADABLE
            findViewById<TextView>(R.id.folder_empty_title)?.setText(
                    if (unreadable) R.string.folder_unreadable_title else R.string.folder_empty_title)
            findViewById<TextView>(R.id.folder_empty_hint)?.setText(
                    if (unreadable) R.string.folder_unreadable_hint else R.string.folder_empty_hint)
        }
    }

    private inner class FileListAdapter : BaseAdapter() {
        override fun getCount(): Int {
            return list.size + 1
        }

        override fun getItem(position: Int): Any? {
            return null
        }

        override fun getItemId(position: Int): Long {
            return 0
        }

        override fun getView(position: Int, v: View?, parent: ViewGroup): View {
            var convertView = v
            if (convertView == null) {
                convertView = LayoutInflater.from(this@FolderSelectorActivity).inflate(R.layout.item_folder, parent, false)
            }
            val row = convertView?.findViewById<View>(R.id.item_folder_row)
            val iconBox = convertView?.findViewById<View>(R.id.item_folder_icon_box)
            val icon = convertView?.findViewById<ImageView>(R.id.item_folder_icon)
            val name = convertView?.findViewById<TextView>(R.id.item_folder_name)
            val chevron = convertView?.findViewById<View>(R.id.item_folder_chev)
            if (position == 0) {
                // 「返回上级」不再是名字叫 ".." 的文件夹：换图标、去掉圆角底和右侧箭头，
                // 文字降级为次级色，一眼就能看出它和下面的文件夹不是一类。
                row?.setBackgroundColor(resources.getColor(R.color.color_surface_muted))
                iconBox?.setBackgroundResource(0)
                icon?.setImageResource(R.drawable.ic_arrow_up)
                name?.text = getString(R.string.folder_back_to_parent)
                name?.setTextColor(resources.getColor(R.color.color_text_secondary))
                name?.textSize = 13f
                chevron?.visibility = View.GONE
                return convertView!!
            }
            row?.setBackgroundColor(resources.getColor(R.color.color_card_background))
            iconBox?.setBackgroundResource(R.drawable.bg_folder_icon)
            icon?.setImageResource(R.drawable.ic_folder_outline)
            name?.text = list[position - 1].name
            name?.setTextColor(resources.getColor(R.color.color_text_dark))
            name?.textSize = 14f
            chevron?.visibility = View.VISIBLE
            return convertView!!
        }
    }
}
