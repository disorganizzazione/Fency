package disorganizzazione.fency

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.annotation.CallSuper
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.view.View
import android.widget.Toast
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

import kotlinx.android.synthetic.main.activity_duel_mode.*
import kotlinx.android.synthetic.main.fragment_go.*
import kotlinx.android.synthetic.main.fragment_ready.*

class DuelModeActivity: FencyModeActivity(){

    companion object {
        private const val REQUEST_CODE_REQUIRED_PERMISSIONS = 1
        private val REQUIRED_PERMISSIONS = arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.ACCESS_COARSE_LOCATION)

        private val STRATEGY = Strategy.P2P_STAR

        private const val H_A_BYTE = 0.toByte()
        private const val L_A_BYTE = 1.toByte()
        private const val DRAW_BYTE = 2.toByte()
        private const val SCORE_BYTE = 3.toByte()

        /** Returns true if the app was granted all the permissions. Otherwise, returns false.  */
        private fun hasPermissions(context: Context, vararg permissions: String): Boolean {
            for (permission in permissions) {
                if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
            return true
        }
    }

    override fun onStart() {
        super.onStart()

        if (android.os.Build.VERSION.SDK_INT >= 23)
            if (!hasPermissions(this, *REQUIRED_PERMISSIONS)) {
                requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CODE_REQUIRED_PERMISSIONS)
            }
    }

    /** Handles user acceptance (or denial) of our permission request.  */
    @CallSuper
    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != REQUEST_CODE_REQUIRED_PERMISSIONS) {
            return
        }

        for (grantResult in grantResults) {
            if (grantResult == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(this, R.string.error_missing_permissions, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }
        recreate()
    }

    private val signum = TriaNomina().toString()
    private var adversatorSigna: String? = null

    private var connectionsClient: ConnectionsClient? = null
    private var opponentEndpointId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_duel_mode) // do not change order
        cntFullScreen = fullscreen_content
        super.onCreate(savedInstanceState)

        ludum!!.maxScore = 3

        connectionsClient = Nearby.getConnectionsClient(this)

        switchToFragment(ReadyFragment())

    }

    private fun switchToFragment(fragment: Fragment){
        val fragmentManager = supportFragmentManager
        val fragmentTransaction = fragmentManager.beginTransaction()
        fragmentTransaction.replace(R.id.fragment_container, fragment)
        fragmentTransaction.commit()
    }

    fun onReady() {
        signaText.text = signum
        findSomeone()
    }

    private fun findSomeone(){
        makeSnackbar(R.string.scanning)

        disableButtons()

        connectionsClient!!.startAdvertising(
                signum, packageName, connectionLifecycleCallback,
                AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
        connectionsClient!!.startDiscovery(
                packageName, endpointDiscoveryCallback,
                DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
    }


    // Callbacks for finding other devices
    private val connectionLifecycleCallback = object :  ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {

            opponentEndpointId = endpointId
            adversatorSigna = connectionInfo.endpointName
            adveText.text = adversatorSigna

            onSomeoneFound()
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {

            when (result.status.statusCode){
                ConnectionsStatusCodes.STATUS_OK -> {
                    makeSnackbar(R.string.connected)

                    disableButtons()
                    switchToFragment(GoFragment())
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    makeSnackbar(R.string.rejected)

                    adveText.setText(R.string.dots)
                    findSomeone()
                }
                ConnectionsStatusCodes.STATUS_ERROR -> {
                    makeSnackbar(R.string.connection_failed)
                    //TODO
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            resetGame()
            findSomeone()
        }
    }

    // Callbacks for finding other devices
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            makeSnackbar(R.string.endpoint_found)
            connectionsClient!!.requestConnection(signum, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            makeSnackbar(R.string.endpoint_lost)
        }
    }

    private fun onSomeoneFound(){
        connectionsClient!!.stopDiscovery()
        connectionsClient!!.stopAdvertising()
        enableButtons("Ready")
    }

    private fun disableButtons(){
        cancelBtn.setOnClickListener(null)
        acceptBtn.setOnClickListener(null)
        cancelBtn.visibility = View.INVISIBLE
        acceptBtn.visibility = View.INVISIBLE
    }

    private fun enableButtons(state : String){
        cancelBtn.visibility = View.VISIBLE
        acceptBtn.visibility = View.VISIBLE
        when (state){
            "Ready" -> {
                cancelBtn.setOnClickListener {
                    connectionsClient!!.rejectConnection(opponentEndpointId!!)
                    disableButtons()
                }
                acceptBtn.setOnClickListener {
                    connectionsClient!!.acceptConnection(opponentEndpointId!!, payloadCallback)
                    disableButtons()
                    makeSnackbar(R.string.connection_pending)
                }
            }
            "End" -> {
                cancelBtn.setOnClickListener {
                    switchToFragment(ReadyFragment())
                }
                acceptBtn.setOnClickListener {
                    switchToFragment(GoFragment()) //TODO!
                }
            }
        }
    }

    fun onGo() {
        sensorHandler!!.registerListeners()
    }

    override fun updatePlayerView(caller: Player) {
        super.updatePlayerView(caller)
        val status: Int = caller.state
        if(caller == usor) {
            if (status == R.integer.HIGH_ATTACK ){
                sendPayload(H_A_BYTE)
            } else if (status == R.integer.LOW_ATTACK) {
                sendPayload(L_A_BYTE)
            }
        }
    }

    private fun sendPayload(byte : Byte) {
        connectionsClient!!.sendPayload(opponentEndpointId!!,Payload.fromBytes(
                ByteArray(1) {return@ByteArray byte}
        ))

    }

    // Callbacks for receiving payloads
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val payloadByte = payload.asBytes()!![0]
            when (payloadByte){
                H_A_BYTE -> {
                    adversator!!.state = R.integer.HIGH_ATTACK
                    gameSync()
                }
                L_A_BYTE -> {
                    adversator!!.state = R.integer.LOW_ATTACK
                    gameSync()
                }
                DRAW_BYTE -> ludum!!.state = R.integer.GAME_DRAW
                SCORE_BYTE -> ludum!!.state = R.integer.GAME_P1
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    fun gameSync() {
        if(ludum!!.score2())
            sendPayload(SCORE_BYTE)
        else
            sendPayload(DRAW_BYTE)
    }

    override fun updateGameView() { // do not call before onGo
        super.updateGameView()

        //end of match
        when(ludum!!.state){
            R.integer.GAME_DRAW -> {
            }
            R.integer.GAME_P1 -> {
                scoreText.text = String.format(getString(R.string.score), ludum!!.score1, ludum!!.score2)
            }
            R.integer.GAME_P2 -> {
                scoreText.text = String.format(getString(R.string.score), ludum!!.score1, ludum!!.score2)
            }
            R.integer.GAME_W1 -> {
                resultText.setText(R.string.won)
                onEnd()
            }
            R.integer.GAME_W2 -> {
                resultText.setText(R.string.lost)
                onEnd()
            }
        }
    }

    private fun onEnd(){
        sensorHandler!!.unregisterListeners()
        enableButtons("End")
    }

    private fun resetGame() {
        opponentEndpointId = null
        adversatorSigna = null
        adveText.setText(R.string.dots)
        ludum!!.reset()
    }

    override fun onPause() {
        connectionsClient?.stopAllEndpoints()
        resetGame()

        super.onPause()
    }

}