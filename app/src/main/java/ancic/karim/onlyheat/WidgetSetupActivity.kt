package ancic.karim.onlyheat

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class WidgetSetupActivity : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, widgetResult())
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_CONFIGURE ||
            provider != ComponentName(this, HeatWidgetReceiver::class.java)
        ) {
            finish()
            return
        }

        placeWidget()
    }

    private fun placeWidget() = lifecycleScope.launch {
        val glanceId = GlanceAppWidgetManager(this@WidgetSetupActivity).getGlanceIdBy(widgetId)
        HeatWidget().update(this@WidgetSetupActivity, glanceId)
        setResult(RESULT_OK, widgetResult())
        finish()
    }

    private fun widgetResult() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
}
