# srs

Spotify Recording Slicer.

This project provides a way to record music from Spotify,
also record the media info from DBus (yes, it's Linux only) with timestamp.
Then slice the recording into individual tracks.

## Recording

First, create a virtual sink for Spotify:

```shell
# use pw-top to decide the parameter used by Spotify
# F32LE 44100 2ch
# it's important to force the rate and quantum to the same value
# otherwise the pipewire will do re-sampling
pw-cli create-node adapter '{
    factory.name       = support.null-audio-sink
    node.name          = "SpotifySink"
    node.description   = "Spotify_Virtual_Cable"
    media.class        = "Audio/Sink"
    audio.format       = "F32LE"
    audio.rate         = 44100
    audio.channels     = 2
    audio.position     = [ FL FR ]
    node.force-rate    = 44100
    node.force-quantum = 8192
    object.linger      = true
}'
# to get rid of it, use pw-top to find the id of the sink, then
# pw-cli destroy <id>
```

Then use something like `pavucontrol` or `qpwgraph` to direct the spotify output to this sink.
Later we will use ffmpeg to record from this sink.

Before recording, ensure you turn off the optimizations in Spotify:

+ Disable the "Normalize volume" option in Spotify settings.
+ Use the highest quality possible.
+ Turn volume to max.
+ Download the music before recording
  + Optional but will improve the quality and stability of the recording.

Once everything is set up, you can start recording by using the `record.sh` script.
Normally, I will run it overnight by using the following command:

```shell
timeout 6h ./record.sh
```

This will yield three files:
+ `session_record.wav`: The recorded audio, will use rf64 if the file is too big.
+ `session_timestamps.txt`: The recorded timestamps and media info (Spotify track id)
+ `session_ffmpeg.log`: The log of ffmpeg command.

The ffmpeg log will provide an exact starting time of the recording.
In conjunction with the timestamps file,
we can calculate the exact starting time of the recording for each track.

Before feeding into the slicer, you can optionally check if recording has been clipped
due to volume set too high:

```shell
ffmpeg -i session_record.wav -af volumedetect -f null /dev/null
```

Pay attention to the `max_volume` value. It should be 0.0dB, it must not be bigger than 0.0dB.
If it's not, it's recommended to change your recording setup and record again.
Or if you don't want to redo the work, use the following command to attenuate the volume:

```shell
ffmpeg -i session_record.wav -af "volume=-1.3dB" -c:a pcm_f32le -sample_fmt flt -rf64 auto session_record_att.wav
```

## Slicer

The slicer is written in Kotlin. It will read the above-mentioned files,
and slice the recording into individual tracks and fill in the metadata.

### Get access to Spotify Web API

To get metadata from Spotify, you need to create an app on 
[Spotify Developer Dashboard](https://developer.spotify.com/dashboard).
You need to check the checkbox for WebApi access,
and set the redirect URI to `http://127.0.0.1:3000/`.

Record the client id and client secret.

### Initialize config for srs

```shell
./srs init -i <client_id> -s <client_secret>
```

This by default will create a `config.json` file in `~/.srs/config.json`.

### Login to Spotify

```shell
./srs login
```

This will print a URL in the console, open it in your browser,
and follow the instructions to login.
If everything goes well, you should be redirected to `http://127.0.0.1:3000/`,
and it will tell you log in is successful, and you can close the tab safely.
Meanwhile, in the console, it will also tell you the login is done.

### Use a different redirect port

If port 3000 is already in use, you can change or add the field `loginServerPort` in the config file,
normally it's located at `~/.srs/config.json`. 

You should also update the settings in the Spotify Developer Dashboard accordingly.

### Slice the recording

```shell
./srs slice -o <output_dir> -c <content_dir>
```

The slicer uses **two-point valley search** to find precise cut points for each track:

+ For each track, it searches for a pair of RMS energy valleys — one near the expected start,
  one near the expected end — whose spacing matches the Spotify-reported track duration.
+ The cut point is placed symmetrically between the valley pair, ensuring equal
  silence padding before and after the track (no cut-head, no cut-tail).
+ Each track is processed independently; errors do not propagate between tracks.

If the slicer cannot find a valid valley pair for a track (e.g., gapless albums),
it **skips** that track and prints a warning. You can fix this by placing non-gapless tracks
around the problematic song in your Spotify playlist and re-recording.

Optional parameter:

+ `--search-window`: Search window radius around expected positions in seconds (default: `10.0`).

The command will automatically find the required files in the current directory.
If you have a different file name or different location for those files,
check out the help message of the `slice` command to see how to specify them.

When slicing the tracks, the slicer will check both the output directory and the content directory
and skip the tracks that have already been sliced (if a file with the same name exists).
Incomplete tracks (e.g., a song already playing when recording started, or a skipped song)
are automatically detected and excluded.

The slicer will write the track files to the output directory.
It's highly recommended to go through the tracks one by one and check if the song ends correctly.
Then you may move them to the content directory, so next time the slicer will skip them.

## Notes

Currently only tested with tracks. Possibly won't work with episodes.

Also, this project is specifically targeted at Linux.
No windows support will be added unless Microsoft decides to bring in dbus to their system.
I appreciate the linux support from Spotify, I enjoy listening to Spotify when I work on Linux.

This project has no means to start a war with Spotify and provide easy access to pirate their music.
I love Spotify, and I would like to pay for their services,
however, I just don't like when my favorite music became gray.
So I wrote this project to maintain a mirror of my favorite music locally.

The software is provided as-is, without any warranty or support.
I am not responsible for any damage caused by using this software.
And you should be shamed if you use this software to share pirate content publicly.