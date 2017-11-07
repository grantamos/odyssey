/*
 * GameActivity.kt
 *
 * Copyright (C) 2017 Odyssey Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.codebutler.odyssey.app.feature.game

import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.support.v7.app.AppCompatActivity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.VISIBLE
import android.widget.ProgressBar
import android.widget.TextView
import com.codebutler.odyssey.R
import com.codebutler.odyssey.app.OdysseyApplication
import com.codebutler.odyssey.app.OdysseyApplicationComponent
import com.codebutler.odyssey.app.feature.common.FpsCalculator
import com.codebutler.odyssey.common.kotlin.bindView
import com.codebutler.odyssey.common.kotlin.isAllZeros
import com.codebutler.odyssey.lib.core.CoreManager
import com.codebutler.odyssey.lib.library.GameLibrary
import com.codebutler.odyssey.lib.library.GameSystem
import com.codebutler.odyssey.lib.library.db.OdysseyDatabase
import com.codebutler.odyssey.lib.library.db.dao.updateAsync
import com.codebutler.odyssey.lib.library.db.entity.Game
import com.codebutler.odyssey.lib.retro.Retro
import com.codebutler.odyssey.lib.retro.RetroDroid
import com.gojuno.koptional.Optional
import com.uber.autodispose.android.lifecycle.AndroidLifecycleScopeProvider
import com.uber.autodispose.kotlin.autoDisposeWith
import dagger.Component
import io.reactivex.Completable
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.functions.Function3
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.io.File
import javax.inject.Inject

class GameActivity : AppCompatActivity() {

    companion object {
        private const val USE_SURFACE = true
        private const val EXTRA_GAME_ID = "game_id"

        fun newIntent(context: Context, game: Game)
                = Intent(context, GameActivity::class.java).apply {
            putExtra(EXTRA_GAME_ID, game.id)
        }
    }

    @Inject lateinit var coreManager: CoreManager
    @Inject lateinit var odysseyDatabase: OdysseyDatabase
    @Inject lateinit var gameLibrary: GameLibrary

    private val gameView: GameSurfaceView by bindView(R.id.game_surface)
    private val imageView: FpsImageView by bindView(R.id.image_view)
    private val progressBar: ProgressBar by bindView(R.id.progress)
    private val fpsView: TextView by bindView(R.id.fps)

    private val handler = Handler()

    private var game: Game? = null
    private var retroDroid: RetroDroid? = null
    private var audioTrack: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)

        val component = DaggerGameActivity_GameComponent.builder()
                .odysseyApplicationComponent((application as OdysseyApplication).component)
                .build()
        component.inject(this)

        // FIXME: Full Activity lifecycle handling.
        if (savedInstanceState != null) {
            return
        }

        val gameId = intent.getIntExtra(EXTRA_GAME_ID, -1)

        odysseyDatabase.gameDao().selectById(gameId)
                .flatMapSingle { game -> prepareGame(game) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .autoDisposeWith(AndroidLifecycleScopeProvider.from(this@GameActivity))
                .subscribe({ data ->
                    odysseyDatabase.gameDao()
                            .updateAsync(data.game.copy(lastPlayedAt = System.currentTimeMillis()))
                            .subscribe()
                    progressBar.visibility = View.GONE
                    loadRetro(data)
                }, { error ->
                    Timber.e("Failed to load game", error)
                    finish()
                })

        if (USE_SURFACE) {
            gameView.visibility = VISIBLE
            gameView.setFpsCallback({ fps: Long -> handler.post({fpsView.text = "$fps"}) })
        } else {
            imageView.visibility = VISIBLE
            imageView.setFpsCallback({ fps: Long -> fpsView.text = "$fps" })
        }

    }

    override fun onResume() {
        super.onResume()
        retroDroid?.start()
    }

    override fun onPause() {
        super.onPause()
        retroDroid?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()

        val saveData = retroDroid?.unloadGame()
        retroDroid?.deinit()

        val game = this.game
        val saveCompletable = if (saveData != null && saveData.isAllZeros().not() && game != null) {
            gameLibrary.setGameSave(game, saveData)
        } else {
            Completable.complete()
        }

        saveCompletable
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe {
                    // This activity runs in its own process which should not live beyond the activity lifecycle.
                    System.exit(0)
                }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        super.dispatchGenericMotionEvent(event)
        retroDroid?.onMotionEvent(event)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        super.dispatchKeyEvent(event)
        retroDroid?.onKeyEvent(event)
        return true
    }

    private fun prepareGame(game: Game): Single<PreparedGameData> {
        val gameSystem = GameSystem.findById(game.systemId)!!

        val coreObservable = coreManager.downloadCore(gameSystem.coreFileName)
        val gameObservable = gameLibrary.getGameRom(game)
        val saveObservable = gameLibrary.getGameSave(game)

        return Single.zip(
                coreObservable,
                gameObservable,
                saveObservable,
                Function3<File, File, Optional<ByteArray>, PreparedGameData> { coreFile, gameFile, saveData ->
                    PreparedGameData(game, coreFile, gameFile, saveData.toNullable())
                })
    }

    private fun loadRetro(data: PreparedGameData) {
        val retroDroid = RetroDroid(this, data.coreFile)

        retroDroid.logCallback = { level, message ->
            val timber = Timber.tag("RetroLog")
            when (level) {
                Retro.LogLevel.DEBUG -> timber.d(message)
                Retro.LogLevel.INFO -> timber.i(message)
                Retro.LogLevel.WARN -> timber.w(message)
                Retro.LogLevel.ERROR -> timber.e(message)
            }
        }

        retroDroid.prepareAudioCallback = { sampleRate ->
            audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .build()
        }

        retroDroid.videoCallback = { bitmap ->
//            Timber.d("${System.currentTimeMillis()}")
            if (USE_SURFACE) {
                gameView.update(bitmap)
            } else {
                handler.post({
                    val drawable = BitmapDrawable(resources, bitmap)
                    drawable.paint.isAntiAlias = false
                    drawable.paint.isDither = false
                    drawable.paint.isFilterBitmap = false
                    imageView.setImageDrawable(drawable)
                })
            }
        }

        retroDroid.audioCallback = { buffer ->
            audioTrack?.let { audioTrack ->
                audioTrack.write(buffer, 0, buffer.size)
                audioTrack.play()
            }
        }

        retroDroid.loadGame(data.gameFile.absolutePath, data.saveData)
        retroDroid.start()

        this.game = data.game
        this.retroDroid = retroDroid
    }

    @Suppress("ArrayInDataClass")
    private data class PreparedGameData(
            val game: Game,
            val coreFile: File,
            val gameFile: File,
            val saveData: ByteArray?)

    @Component(dependencies = arrayOf(OdysseyApplicationComponent::class))
    interface GameComponent {

        fun inject(activity: GameActivity)
    }
}
