package ai.tennisvision.camera

import android.view.View
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/** Local QR camera and decoder, also available on a sideloaded/offline installation. */
class PairingScannerActivity : CaptureActivity() {
  override fun initializeContent(): DecoratedBarcodeView {
    setContentView(R.layout.activity_pairing_scanner)
    findViewById<View>(R.id.cancelScan).setOnClickListener { finish() }
    return findViewById(R.id.pairingBarcodeView)
  }
}
