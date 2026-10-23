package com.github.stjiege.ftpshare.activities

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ListView
import android.widget.Toast
import com.github.stjiege.ftpshare.Constants
import com.github.stjiege.ftpshare.R
import com.github.stjiege.ftpshare.adapers.AccountListAdapter
import com.github.stjiege.ftpshare.services.FtpService
import com.github.stjiege.ftpshare.utils.CommonUtils

/** Displays the built-in anonymous account followed by all named accounts. */
class ServiceAccountActivity : BaseActivity() {
    private lateinit var accountListView: ListView

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        if (FtpService.isFTPServiceRunning()) {
            Toast.makeText(this, R.string.attention_ftp_is_running, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContentView(R.layout.fragment_account)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.item_account_settings)
        accountListView = findViewById(R.id.view_user_list)
        refreshContents()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_service_account, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_main_add -> {
            FtpService.getUserAccountListAsync(this) { accounts ->
                if (isFinishing || isDestroyed) return@getUserAccountListAsync
                if (accounts.size >= Constants.FTPConsts.MAX_NAMED_ACCOUNTS) {
                    Toast.makeText(this, R.string.account_limit_reached, Toast.LENGTH_SHORT).show()
                } else {
                    startActivityForResult(Intent(this, AddAccountActivity::class.java), REQUEST_EDIT_ACCOUNT)
                }
            }
            true
        }
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EDIT_ACCOUNT) refreshContents()
    }

    private fun refreshContents() {
        FtpService.getUserAccountListAsync(this) { accounts ->
            if (isFinishing || isDestroyed) return@getUserAccountListAsync
            accountListView.adapter = AccountListAdapter(this, accounts, accountListView)
        }
    }

    private companion object {
        const val REQUEST_EDIT_ACCOUNT = 1
    }
}

