#!/bin/bash
set -e

# srs path for check
SRS_PATH="./build/install/srs/srs"
# content on my NAS
CONTENT_FOLDER="/run/user/1000/gvfs/smb-share:server=100.99.241.120,share=media/music/spotify-rip"

RECORD_FILE="session_record.wav"
LOG_FILE="session_timestamps.txt"

# ── Start recording: parecord (F32LE) → FIFO → ffmpeg (WAV/RF64) ──
# ffmpeg's -f pulse hardcodes PA_SAMPLE_S16LE requests. Using parecord
# with --format=float32le explicitly requests F32LE from PulseAudio,
# preserving Spotify's native F32LE output through PipeWire.
RECORD_FIFO=$(mktemp -u /tmp/srs_record.XXXXXX)
mkfifo "$RECORD_FIFO"

parecord \
    --device=SpotifySink.monitor \
    --format=float32le --rate=44100 --channels=2 \
    --raw \
    > "$RECORD_FIFO" &
PARECORD_PID=$!

echo "$(date +%s.%3N)" > "$LOG_FILE"

ffmpeg -y \
    -f f32le -ar 44100 -ac 2 -i "$RECORD_FIFO" \
    -c:a pcm_f32le -rf64 auto \
    "$RECORD_FILE" \
    -hide_banner -nostats 2> /dev/null &
FFMPEG_PID=$!

# ── Start playerctl monitoring via FIFO ──
# Using a FIFO decouples playerctl from the read loop, giving us
# explicit PID control over playerctl for clean shutdown.
MONITOR_FIFO=$(mktemp -u /tmp/srs_monitor.XXXXXX)
mkfifo "$MONITOR_FIFO"

playerctl -p spotify metadata -F \
    --format '{{ mpris:trackid }}|{{ status }}' \
    > "$MONITOR_FIFO" 2>/dev/null &
PLAYERCTL_PID=$!

# ── Cleanup: runs on ANY exit (signal or normal) ──
cleanup() {
    # 1. Pause Spotify to stop generating new playback events
    echo "pause spotify..."
    playerctl -p spotify pause 2>/dev/null || true
    # 2. Kill playerctl monitoring so no more timestamps are recorded
    echo "stop playerctl..."
    kill -SIGTERM "$PLAYERCTL_PID" 2>/dev/null || true
    # 3. Kill parecord → ffmpeg sees EOF on FIFO → finalizes WAV header
    echo "stop parecord..."
    kill "$PARECORD_PID" 2>/dev/null || true
    wait "$PARECORD_PID" 2>/dev/null || true
    # 4. Ensure ffmpeg stops (should exit naturally after EOF)
    echo "stop ffmpeg..."
    kill -SIGTERM "$FFMPEG_PID" 2>/dev/null || true
    wait "$FFMPEG_PID" 2>/dev/null || true
    # 5. Clean up FIFOs
    echo "clean up..."
    rm -f "$MONITOR_FIFO" "$RECORD_FIFO"
    echo "done"
}

trap 'cleanup' EXIT SIGHUP

# ── Main monitoring loop ──
LAST_ID=""
while read -r line; do
    NOW=$(date +%s.%3N)

    RAW_ID=$(echo "$line" | awk -F'|' '{print $1}')
    STATUS=$(echo "$line" | awk -F'|' '{print $2}')

    # clean up raw id
    # might be spotify:track:0cMCCfgXNvzpn9AgFJib76
    # or /com/spotify/track/0cMCCfgXNvzpn9AgFJib76
    # we just want 0cMCCfgXNvzpn9AgFJib76
    CLEAN_ID=$(echo "$RAW_ID" | sed 's/.*:track://' | sed 's/.*\/track\///')

    # skip if id is empty
    if [ -z "$CLEAN_ID" ]; then
        continue
    fi

    if [ "$STATUS" != "Playing" ]; then
        # only process playing
        continue
    fi

    # skip to next if the current content is already there
    EXIST=$("$SRS_PATH" check --id "$CLEAN_ID" -f "$CONTENT_FOLDER")
    if [ "$EXIST" = "Already exists." ]; then
        echo "$CLEAN_ID exists, skip..."
        sleep 4
        playerctl -p spotify pause
        sleep 1
        playerctl -p spotify next
        continue
    fi

    echo "$NOW,$CLEAN_ID,$STATUS"
    if [ "$CLEAN_ID" != "$LAST_ID" ]; then
        # record log on playing
        echo "$NOW,$CLEAN_ID" >> "$LOG_FILE"
        LAST_ID="$CLEAN_ID"
    fi
done < "$MONITOR_FIFO"
