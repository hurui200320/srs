#!/bin/bash
set -e

# srs path for check
SRS_PATH="./build/install/srs/srs"
# content on my NAS
CONTENT_FOLDER="/run/user/1000/gvfs/smb-share:server=100.99.241.120,share=media/music/spotify-rip"

RECORD_FILE="session_record.wav"
LOG_FILE="session_timestamps.txt"
FFMPEG_LOG_FILE="session_ffmpeg.log"

# output log to ./ffmpeg.log, this will include the start timestamp
PULSE_LATENCY_MSEC=180 ffmpeg -y \
    -thread_queue_size 4096 \
    -f pulse \
    -ch_layout stereo \
    -ac 2 \
    -ar 44100 \
    -i SpotifySink.monitor \
    -c:a pcm_f32le \
    -sample_fmt flt \
    -rf64 auto \
    "$RECORD_FILE" \
    -hide_banner \
    -nostats 2> "$FFMPEG_LOG_FILE" &
FFMPEG_PID=$!

cleanup() {
    if [ -n "$FFMPEG_PID" ]; then
        kill -SIGTERM "$FFMPEG_PID" 2>/dev/null
        wait "$FFMPEG_PID"
    fi
}

trap 'cleanup' SIGINT SIGTERM


LAST_ID=""
playerctl -p spotify metadata -F --format '{{ mpris:trackid }}|{{ status }}' | while read -r line; do
    NOW=$(date +%s.%3N)

    RAW_ID=$(echo "$line" | awk -F'|' '{print $1}')
    STATUS=$(echo "$line" | awk -F'|' '{print $2}')

    # clean up raw id
    # might `be spotify:track:0cMCCfgXNvzpn9AgFJib76`
    # or `/com/spotify/track/0cMCCfgXNvzpn9AgFJib76`
    # we just want `0cMCCfgXNvzpn9AgFJib76`
    CLEAN_ID=$(echo "$RAW_ID" | sed 's/.*:track://' | sed 's/.*\/track\///')

    # skip if id is empty
    if [ -z "$CLEAN_ID" ]; then
        continue
    fi

    # skip to next if the current content is already there
    EXIST=$("$SRS_PATH" check --id "$CLEAN_ID" -f "$CONTENT_FOLDER")
    if [ "$EXIST" = "Already exists." ]; then
        echo "$CLEAN_ID exists, skip..."
        sleep 5
        playerctl -p spotify next
        continue
    fi

    echo "$NOW,$CLEAN_ID,$STATUS"

    if [ "$CLEAN_ID" != "$LAST_ID" ] && [ "$STATUS" == "Playing" ]; then
        # record log on playing
        echo "$NOW,$CLEAN_ID" >> "$LOG_FILE"
        LAST_ID="$CLEAN_ID"
    fi
done