package com.momentum.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentum.app.data.Api
import com.momentum.app.data.Conversation
import com.momentum.app.data.ConversationsResponse
import com.momentum.app.data.Prefs
import com.momentum.app.ui.Lang
import com.momentum.app.ui.MomentumTheme
import com.momentum.app.ui.PixelIcon
import com.momentum.app.ui.Px
import com.momentum.app.ui.T
import com.momentum.app.ui.stringsFor

/** Shown when a group-ranking widget is placed (or reconfigured): pick the group it shows. */
class GroupWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val prefs = Prefs(this)
        val S = stringsFor(if (prefs.language == "de") Lang.DE else Lang.EN)
        setContent {
            MomentumTheme {
                var groups by remember { mutableStateOf<List<Conversation>?>(null) }
                var failed by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    val token = prefs.token
                    if (token == null) { failed = true; return@LaunchedEffect }
                    val api = Api(prefs.serverUrl, token, prefs.language)
                    groups = runCatching {
                        api.json.decodeFromString(ConversationsResponse.serializer(), api.raw("GET", "/conversations"))
                            .conversations.filter { it.isGroup }
                    }.getOrNull()
                    failed = groups == null
                }
                Column(Modifier.fillMaxSize().background(Px.Bg).safeDrawingPadding().padding(16.dp)) {
                    Text(S.widgetPickGroup, style = T.title.copy(fontSize = 22.sp))
                    Spacer(Modifier.width(8.dp))
                    when {
                        failed -> Text(S.needsInternet, style = T.small, modifier = Modifier.padding(top = 16.dp))
                        groups?.isEmpty() == true -> Text(S.empty("group").first, style = T.small, modifier = Modifier.padding(top = 16.dp))
                    }
                    LazyColumn(Modifier.padding(top = 12.dp)) {
                        items(groups.orEmpty(), key = { it.id }) { c ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    WidgetGroups.set(this@GroupWidgetConfigActivity, widgetId, c.id, c.name)
                                    val mgr = AppWidgetManager.getInstance(this@GroupWidgetConfigActivity)
                                    drawGroupRanking(this@GroupWidgetConfigActivity, mgr, widgetId)
                                    RankingWorker.now(this@GroupWidgetConfigActivity)
                                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                    finish()
                                }.padding(vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PixelIcon("ui_group", 22.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(c.name, style = T.bodyStrong)
                            }
                        }
                    }
                }
            }
        }
    }
}
