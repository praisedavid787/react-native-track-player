package com.doublesymmetry.trackplayer

import com.doublesymmetry.trackplayer.module.MusicModule
import com.facebook.react.TurboReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.bridge.ReactApplicationContext

/**
 * TrackPlayer
 * https://github.com/react-native-kit/react-native-track-player
 * @author Milen Pivchev @mpivchev
 */
class TrackPlayer : TurboReactPackage() {
    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
        return if (name == MusicModule.NAME) {
            MusicModule(reactContext)
        } else {
          null
        }
    }

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider {
      return ReactModuleInfoProvider {
        val moduleInfos: MutableMap<String, ReactModuleInfo> = HashMap()
        moduleInfos[MusicModule.NAME] = ReactModuleInfo(
          MusicModule.NAME,
          MusicModule.NAME,
          false,  // canOverrideExistingModule
          false,  // needsEagerInit
          true,  // hasConstants
          false,  // isCxxModule
          true // isTurboModule
        )
        moduleInfos
      }
    }
}