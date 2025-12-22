# Build instructions to port changes from androidx/media3 to google/ExoPlayer (v2.16.1 and above)

If you want to use LCEVC Decoding on ExoPlayer but have not updated your repository to androidx/media3, we have provided a python script to assist in porting the androidx/media3 library (libraries/decoder_lcevc) to an google/ExoPlayer extension (extensions/LCEVC).

The python script is located in the root of the project and running `python exoplayerAddLcevc.py -h` will provide you the instructions needed to port the changes.

```
usage: exoplayerAddLcevc.py [-h] [--exoPlayerVersion EXOPLAYERVERSION] [--location LOCATION]

LCEVC Python patch automatically transitions LCEVCMediaCodecAdapter as library/decoder_lcevc in androidx/media3, to Google ExoPlayer Extension as extension/lcevc based on the provided version at : https://github.com/google/ExoPlayer.git.
This patch will clone the version of Google ExoPlayer provided in the argument and apply changes from androidX/media3 with the relevant alterations to the namespace and packages.
options:
  -h, --help            show this help message and exit
  --exoPlayerVersion EXOPLAYERVERSION

                        Version of Google ExoPlayer that the changes needs to be applied to. 
                        Example usage:  python exoplayerAddLcevc.py --exoPlayerVersion 2.18 --location "..\EXOPLAYER" 

  --location LOCATION   
                        Location on the disk where the repo should be cloned.
                        Example usage:  python exoplayerAddLcevc.py --exoPlayerVersion 2.18 --location "..\EXOPLAYER" 
```

Once you run this command you have to provide the version of exo player that you want to port the changes to. 

Note this needs to be 2.16.1 and above as `DefaultVideoRenderers` didn't have mediacodecAdapter until 2.16.1.

This command will clone the repository based on the provided Exoplayer version to your specified location, moving the relevant files to the new location and changing the package names and folder structure as needed for the project.

Once the script exits with no errors you can open the repo and build the project. 

Note : All the changes are based on the flavour for LCEVC exactly as the androidx/media3 implementation. So you might have to enable the correct flavour for LCEVC to be enabled.