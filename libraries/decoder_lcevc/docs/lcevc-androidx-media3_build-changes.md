
# Adding decoder_lcevc to your your AndroidX/Media project
The LCEVC decoder library can be added to your AndroidX/Media project from either source code or from a maven package. Instructions for the two cases follow.

## Case 1: use decoder_lcevc source
To add the `decoder_lcevc` library as source code in your AndroidX/Media project you need to:
1. Add the `decoder_lcevc` directory tree under `libraries` in your project;
2. Include `decoder_lcevc` in your settings.gradle or core_settings.gradle:
```
include modulePrefix + 'lib-decoder-lcevc'
project(modulePrefix + 'lib-decoder-lcevc').projectDir = new File(rootDir, 'libraries/decoder_lcevc')
```
3. This module depends on a packaged library called lcevc-dil, therefore add the following maven repository source to your root build.gradle, in this way:
```
allprojects {
    repositories {
        // Other existing repo sources
        // ...
        // Add the following block
        maven {
            name = "v-nova"
            url "https://gitlab.com/api/v4/groups/v-nova-group/-/packages/maven"
        }
    }
}
```
4. In the `DemoUtil` class or the equivalent in your own project, make sure the `ExoPlayer.Builder` calls the set function to use the `LcevcRenderersFactory` as a replacement of the default `DefaultRenderersFactory`:
```
    boolean lcevcEnabled = true;  // put your switching logic here
    RenderersFactory renderersFactory = lcevcEnabled ? new LcevcRenderersFactory(applicationContext) : new DefaultRenderersFactory(applicationContext);
    player = new ExoPlayer.Builder(this)
        .setRenderersFactory(renderersFactory)
        .build();
```

## Case 2: use decoder_lcevc maven pkg
The `decoder_lcevc` module found here is also available as a maven package on the V-Nova public github repo.
To add the `decoder_lcevc` library as a maven dependency in your AndroidX/Media project you need to:
1. Add the dependency for the `decoder_lcevc` maven pkg in the build.gradle of your player app:
```
dependencies {
    // Other dependencies
    // ...
    // Add the following line:
    implementation 'androidx.media3:media3-decoder-lcevc:1.2.1-241114-d1b69cc'
}
```
Note: the pkg version above is an example, please liaise with your V-Nova representative for the correct version to use.
2. Add the V-Nova github pkg repository, as per point 3 above;
3. Set the `ExoPlayer.Builder` to use the `LcevcRenderersFactory` as per point 4 above.

## Build flavour `withDecoderExtensionsWithLcevc` in `demos/main`

To enable playback of LCEVC contents in the `demos/main` app just use the new flavour `withDecoderExtensionsWithLcevc`. This flavour replaces the default `DemoUtil` class with oen that uses the `LcevcRenderersFactory` as described above.
