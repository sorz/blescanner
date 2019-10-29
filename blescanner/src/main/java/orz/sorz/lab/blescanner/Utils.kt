package orz.sorz.lab.blescanner

import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onDismiss
import kotlinx.coroutines.CompletableDeferred

suspend fun MaterialDialog.awaitDismiss() {
    val dismissed = CompletableDeferred<Unit>()
    onDismiss { dismissed.complete(Unit) }
    dismissed.await()
}
