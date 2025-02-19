#!/usr/bin/env python3
import sys
import os
import subprocess
from pathlib import Path
import argparse
import textwrap
import shutil

changes = [
    ["androidx.media3.common.Format", "com.google.android.exoplayer2.Format"],
    ["androidx.media3.common.util.Log", "com.google.android.exoplayer2.util.Log"],
    ["androidx.media3.common.C", "com.google.android.exoplayer2.C"],
    ["androidx.media3.common.util.Util", "com.google.android.exoplayer2.util.Util"],

    ["androidx.media3.exoplayer.DefaultRenderersFactory", "com.google.android.exoplayer2.DefaultRenderersFactory"],
    ["androidx.media3.exoplayer.Renderer", "com.google.android.exoplayer2.Renderer"],
    ["androidx.media3.exoplayer.RenderersFactory", "com.google.android.exoplayer2.RenderersFactory"],
    ["androidx.media3.exoplayer.mediacodec.MediaCodecSelector", "com.google.android.exoplayer2.mediacodec.MediaCodecSelector"],
    ["androidx.media3.exoplayer.video.MediaCodecVideoRenderer", "com.google.android.exoplayer2.video.MediaCodecVideoRenderer"],
    ["androidx.media3.exoplayer.video.VideoRendererEventListener", "com.google.android.exoplayer2.video.VideoRendererEventListener"],

    ["androidx.media3.common.util.Assertions.checkNotNull", "com.google.android.exoplayer2.util.Assertions.checkNotNull"],
    ["import static androidx.media3.common.util.MediaFormatUtil.isVideoFormat;", ""],
    ["import androidx.media3.common.util.UnstableApi;", ""],
    ["androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory",
        "com.google.android.exoplayer2.mediacodec.DefaultMediaCodecAdapterFactory"],
    ["androidx.media3.exoplayer.mediacodec.MediaCodecAdapter", "com.google.android.exoplayer2.mediacodec.MediaCodecAdapter"],
    ["androidx.media3.exoplayer.mediacodec.SynchronousMediaCodecAdapter", "com.google.android.exoplayer2.mediacodec.SynchronousMediaCodecAdapter"],
    ["new DefaultMediaCodecAdapterFactory().createAdapter(configuration);", ""],
    ["new LcevcSynchronousMediaCodecAdapter.Factory().createAdapter(configuration) :", ""],
    ["return isVideoFormat(configuration.mediaFormat) ?", "return new LcevcSynchronousMediaCodecAdapter.Factory().createAdapter(configuration);"],
    ["androidx.media3.util.TraceUtil", "com.google.android.exoplayer2.util.TraceUtil"],
    ["androidx.media3.common.util.TraceUtil", "com.google.android.exoplayer2.util.TraceUtil"],
    ["androidx.media3.decoder.CryptoInfo", "com.google.android.exoplayer2.decoder.CryptoInfo"],
    ["androidx.media3.common.util.NonNullApi", "com.google.android.exoplayer2.util.NonNullApi"],
    ["gradle.ext.androidxMediaSettingsDir/", "gradle.ext.exoplayerSettingsDir/"],
    ["namespace 'com.vnova.lcevc.decoder'", ""],
    ["implementation project(modulePrefix + 'lib-decoder'", "implementation project(modulePrefix + 'library-decoder'"],
    ["implementation project(modulePrefix + 'lib-exoplayer'", "implementation project(modulePrefix + 'library-core'"],
    ["testImplementation project(modulePrefix + 'test-utils')", "testImplementation project(modulePrefix + 'testutils')"],
    ["@UnstableApi", ""]
]


def print_error(error):
    print(error)
    print('Usage: python exoplayerAddLcevc.py -h')
    print()


def folder_exists(file):
    # Check if a file exists
    if os.path.exists(file):
        return True
    return False


def inplace_change(filename, old_string, new_string, reportFatal=False):
    # Safely read the input filename using 'with'
    with open(filename) as f:
        s = f.read()
        if old_string not in s:
            if reportFatal:
                print('FATAL Error occurred while applying patch. Could not find snippet of code.')
                print('"{old_string}" not found in {filename}.'.format(**locals()))
            return

    # Safely write the changed content, if found in the file
    with open(filename, 'w') as f:
        # print('Changing '+filename)
        s = s.replace(old_string, new_string)
        f.write(s)


def copy_and_patch_files(location):
    # Copies mainLcevc replacement to demos main src
    src = os.path.join(location, 'demos', 'main', 'src', 'main', 'java', 'com', 'google', 'android', 'exoplayer2', 'demo', 'DemoUtil.java')
    trg = os.path.join(location, 'demos', 'main', 'src', 'mainLcevc', 'java', 'com', 'google', 'android', 'exoplayer2', 'demo')
    os.makedirs(trg)
    shutil.copy2(src, trg)

    # Copies the whole lcevc block to the exo player repo
    src = os.path.join('libraries', 'decoder_lcevc')
    trg = os.path.join(location, 'extensions', 'lcevc')
    shutil.copytree(src, trg, ignore=shutil.ignore_patterns('.cxx', 'buildout'))

    # Rename the content in the LCEVC Files to change package names
    dir = os.path.join(trg, 'src', 'main', 'java', 'com', 'vnova', 'lcevc', 'decoder')
    arr = os.listdir(dir)
    for file in arr:
        for change in changes:
            inplace_change(os.path.join(dir, file), change[0], change[1], False)

    # Apply changes on lcevc build.gradle
    files = [os.path.join(trg, 'build.gradle'), os.path.join(trg, 'publish.gradle')]
    for change in changes:
        for file in files:
            inplace_change(file, change[0], change[1], False)

    # Changes in settings.gradle ##################################################################################################################
    file = os.path.join(location, 'settings.gradle')
    old = '''apply from: '''
    new = '''include modulePrefix + 'extension-lcevc'
project(modulePrefix + 'extension-lcevc').projectDir = new File(rootDir, 'extensions/lcevc')

apply from: '''
    inplace_change(file, old, new, True)

    # Changes in build.gradle #####################################################################################################################
    file = os.path.join(location, 'build.gradle')
    old = '''allprojects {
    repositories {
        google()
        mavenCentral()
    }
'''
    new = '''allprojects {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "v-nova"
            url "https://gitlab.com/api/v4/groups/v-nova-group/-/packages/maven"
        }
    }
'''
    inplace_change(file, old, new, True)

    # Changes in demos/main/build.gradle ##########################################################################################################
    file = os.path.join(location, 'demos', 'main', 'build.gradle')
    old = '''
    productFlavors {
        noDecoderExtensions {
            dimension "decoderExtensions"
            buildConfigField "boolean", "USE_DECODER_EXTENSIONS", "false"
        }
        withDecoderExtensions {
            dimension "decoderExtensions"
            buildConfigField "boolean", "USE_DECODER_EXTENSIONS", "true"
        }
    }
}'''
    new = '''
    productFlavors {
        noDecoderExtensions {
            dimension "decoderExtensions"
            buildConfigField "boolean", "USE_DECODER_EXTENSIONS", "false"
        }
        // This will enable all extensions excluding the MPEG-5 Part 2 (LCEVC) one
        withDecoderExtensions {
            dimension "decoderExtensions"
            buildConfigField "boolean", "USE_DECODER_EXTENSIONS", "true"
        }
        // This will enable all extensions including the MPEG-5 Part 2 (LCEVC) one
        withDecoderExtensionsWithLcevc {
            dimension "decoderExtensions"
            buildConfigField "boolean", "USE_DECODER_EXTENSIONS", "true"
        }
    }

    sourceSets {
        noDecoderExtensions {
            java.srcDirs = ['src/main']
        }
        withDecoderExtensions {
            java.srcDirs = ['src/main']
        }
        withDecoderExtensionsWithLcevc {
            java.srcDirs += 'src/mainLcevc'
            main {
                java {
                    exclude '**/DemoUtil.java'
                }
            }
        }
    }
}'''
    inplace_change(file, old, new, True)

    old = '''implementation project(modulePrefix + 'extension-ima')'''
    new = '''implementation project(modulePrefix + 'extension-ima')
    withDecoderExtensionsWithLcevcImplementation project(modulePrefix + 'extension-lcevc')
'''
    inplace_change(file, old, new, True)

    old = 'shrinkResources true'
    new = 'shrinkResources false'
    inplace_change(file, old, new, True)

    old = 'minifyEnabled true'
    new = 'minifyEnabled false'
    inplace_change(file, old, new, True)

    # Changes in demos/main/src/main/assets/media.exolist.json ####################################################################################
    file = os.path.join(location, 'demos', 'main', 'src', 'main', 'assets', 'media.exolist.json')
    old = '''[
  {
    "name": "Clear DASH",'''
    new = '''[
  {
    "name": "LCEVC",
    "samples": [
      {
        "name": "HD (MP4, H264)",
        "uri": "https://dyctis843rxh5.cloudfront.net/vnIAZIaowG1K7qOt/master.m3u8"
      }
    ]
    },{
    "name": "Clear DASH",'''
    inplace_change(file, old, new, True)

    # Changes in demos/main/src/mainLcevc/java/com/google/android/exoplayer2/demo/DemoUtil.java ###################################################
    file = os.path.join(location, 'demos', 'main', 'src', 'mainLcevc', 'java', 'com', 'google', 'android', 'exoplayer2', 'demo', 'DemoUtil.java')
    old = '''import com.google.android.exoplayer2.DefaultRenderersFactory;'''
    new = '''import com.vnova.lcevc.decoder.LcevcRenderersFactory;
import com.google.android.exoplayer2.DefaultRenderersFactory;
'''
    inplace_change(file, old, new, True)

    old = '''    return new DefaultRenderersFactory(context.getApplicationContext())'''
    new = '''    return new LcevcRenderersFactory(context.getApplicationContext())'''
    inplace_change(file, old, new, True)

    # Changes in library/core/build.gradle ########################################################################################################
    file = os.path.join(location, 'library', 'core', 'build.gradle')
    old = '''    sourceSets {
 androidTest.assets.srcDir '../../testdata/src/test/assets/'
 test.assets.srcDir '../../testdata/src/test/assets/'
    }'''
    new = '''    sourceSets {
        androidTest.assets.srcDir '../../testdata/src/test/assets/'
        test.assets.srcDir '../../testdata/src/test/assets/'
    }

    publishing {
        singleVariant('release')
    }'''
    inplace_change(file, old, new, True)

    old = ''' releaseDescription = 'The ExoPlayer library core module.\''''
    new = ''' releaseDescription = 'The Lcevc compatible ExoPlayer library core module.\''''
    inplace_change(file, old, new, True)

    # Changes in library/core/src/main/java/com/google/android/exoplayer2/util/DebugTextViewHelper.java ###########################################
    file = os.path.join(location, 'library', 'core', 'src', 'main', 'java', 'com', 'google', 'android', 'exoplayer2', 'util',
                        'DebugTextViewHelper.java')
    old = '''import android.widget.TextView;'''
    new = '''import android.widget.TextView;
 import com.google.android.exoplayer2.video.VideoSize;'''
    inplace_change(file, old, new, True)

    old = '''    Format format = player.getVideoFormat();
    DecoderCounters decoderCounters = player.getVideoDecoderCounters();
'''
    new = '''    Format format = player.getVideoFormat();
    VideoSize videoSize = player.getVideoSize();
    DecoderCounters decoderCounters = player.getVideoDecoderCounters();
'''
    inplace_change(file, old, new, True)

    old = '''        + format.width
        + "x"
        + format.height
        + getPixelAspectRatioString(format.pixelWidthHeightRatio)
'''
    new = '''        + videoSize.width
        + "x"
        + videoSize.height
        + getPixelAspectRatioString(videoSize.pixelWidthHeightRatio)
'''
    inplace_change(file, old, new, True)

    # Changes in extensions/lcevc/build.gradle ####################################################################################################
    file = os.path.join(location, 'extensions', 'lcevc', 'build.gradle')
    old = '''    releaseArtifactId = 'media3-decoder-lcevc'
    releaseName = 'Media3 Lcevc decoder module\''''
    new = '''    releaseArtifactId = 'extension-lcevc'
    releaseName = 'LCEVC extension for ExoPlayer.\''''
    inplace_change(file, old, new, True)

    ###############################################################################################################################################


def clone_and_modify(exoplayer_version, location):
    if not os.path.exists(location):
        print('Location does not exist')
        sys.exit()
    git_url = "https://github.com/google/ExoPlayer.git"
    try:
        response = subprocess.check_output(["git", "ls-remote", "-t", git_url, 'r' + exoplayer_version])
    except subprocess.CalledProcessError as e:
        print_error(e.output)
        sys.exit()
    else:
        response = response.decode("utf-8")
        if response != '':
            tag_name = response.split('refs/tags/')[1].split('\n')[0]
            print('Version exists: ' + response)
            Path(location).mkdir(exist_ok=True)
            subprocess.check_call(["git", "clone", "--depth", "1", "--branch", tag_name, git_url, location])
            copy_and_patch_files(location)
            print('Success.')
        else:
            print_error("Provided Version: " + exoplayer_version + " not found in ExoPlayer repo: https://github.com/google/ExoPlayer.git")
            sys.exit()


parser = argparse.ArgumentParser(description=textwrap.dedent('''This script patches the Google ExoPlayer repository \
(https://github.com/google/ExoPlayer.git) to add LCEVC support by applying the same or equivalent changes as made by V-Nova to androidX/media3.
'''), formatter_class=argparse.RawTextHelpFormatter)
parser.add_argument('--exoPlayerVersion', help=textwrap.dedent(r'''
        Version of Google ExoPlayer that the changes needs to be applied to.
        Example usage:  python exoplayerAddLcevc.py --exoPlayerVersion 2.18 --location "..\EXOPLAYER"

         '''))
parser.add_argument('--location', help=textwrap.dedent(r'''
        Location on the disk where the repo should be cloned.
        Example usage:  python exoplayerAddLcevc.py --exoPlayerVersion 2.18 --location "..\EXOPLAYER"
        '''))

args = parser.parse_args()
config = vars(args)
if config['exoPlayerVersion'] is None:
    print_error('No exoplayer version provided. Kindly provide a valid exoplayerVersion.')
    exit
elif config['location'] is None:
    print_error('No valid location on the disk was provided for the repo to be cloned.')
    exit
else:
    clone_and_modify(config['exoPlayerVersion'], config['location'])
