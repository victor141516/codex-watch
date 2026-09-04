#include <assert.h>
#include <errno.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "bsp/esp-bsp.h"
#include "cJSON.h"
#include "driver/i2c_master.h"
#include "esp_err.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/stream_buffer.h"
#include "freertos/task.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "lvgl.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "nvs_flash.h"
#include "os/os_mbuf.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

LV_FONT_DECLARE(font_montserrat_14_latin);
LV_FONT_DECLARE(font_montserrat_18_latin);

#ifndef CODEX_WATCH_DIAGNOSTIC
#define CODEX_WATCH_DIAGNOSTIC 0
#endif

#define DEVICE_NAME "Codex Watch"
#define MAX_FRAME_BYTES (32 * 1024)
#define MAX_THREADS 8
#define AUDIO_SAMPLE_RATE 16000
#define AUDIO_MAX_SECONDS (10 * 60)
#define AUDIO_PACKET_BYTES 232
#define AUDIO_CAPTURE_BYTES 1024
#define AUDIO_STREAM_BYTES (32 * 1024)
#define AUDIO_STREAM_STORAGE_BYTES (AUDIO_STREAM_BYTES + 1)
#define AUDIO_WARMUP_PACKETS 16
#define AUDIO_SENDER_CORE 0
#define AUDIO_CAPTURE_CORE 1
#define AUDIO_TASK_PRIORITY 3
#define BOOT_BUTTON_GPIO GPIO_NUM_0
#define DISPLAY_BRIGHTNESS 72
#define DISPLAY_IDLE_TIMEOUT_MS 30000
#define FULL_POWER_OFF_AFTER_MS (5 * 60 * 1000)
#define POWER_POLL_MS 200
#define BATTERY_POLL_MS 10000
#define DISPLAY_WIDTH 368
#define DISPLAY_HEIGHT 448
#define BATTERY_BAR_MARGIN 15
#define BATTERY_BAR_Y 3
#define BATTERY_BAR_MAX_WIDTH (DISPLAY_WIDTH - (2 * BATTERY_BAR_MARGIN))
#define AXP2101_ADDRESS 0x34
#define AXP2101_STATUS1_REG 0x00
#define AXP2101_STATUS2_REG 0x01
#define AXP2101_CHIP_ID_REG 0x03
#define AXP2101_ADC_CTRL_REG 0x30
#define AXP2101_BAT_VOLTAGE_REG 0x34
#define AXP2101_BAT_PERCENT_REG 0xa4
#define AXP2101_COMMON_CONFIG_REG 0x10
#define AXP2101_COMMON_CONFIG_SOFT_OFF BIT(0)
#define THREAD_ID_LEN 40
#define TITLE_LEN 96
#define PREVIEW_LEN 160
#define LAST_AGENT_MESSAGE_LEN (24 * 1024)

#define WATCH_STATE_MAGIC 0x43575354u /* "CWST" */
#define WATCH_STATE_VERSION 1u
#define WATCH_STATE_MAX_THREADS MAX_THREADS
#define WATCH_STATE_PATH_A "/spiffs/watch_state_a.bin"
#define WATCH_STATE_PATH_B "/spiffs/watch_state_b.bin"
#define WATCH_STATE_PATH_TMP "/spiffs/watch_state.tmp"
#define WATCH_STATE_CHUNK_BYTES 1024

typedef enum {
    WATCH_VIEW_LIST = 0,
    WATCH_VIEW_CONVERSATION = 1,
} watch_view_t;

typedef struct {
    uint32_t magic;
    uint32_t version;
    uint32_t sequence;
    uint32_t payload_size;
    uint32_t payload_hash;
} watch_state_header_t;

typedef struct {
    uint32_t view;
    int32_t list_scroll_y;
    int32_t response_scroll_y;
    char selected_thread_id[THREAD_ID_LEN];
    uint32_t thread_count;
} watch_state_meta_t;

static const char *TAG = "codex_watch";

/* 7e57c001-7f76-4f1a-9b6d-1c2f8a10c001 */
static const ble_uuid128_t SERVICE_UUID = BLE_UUID128_INIT(
    0x01, 0xc0, 0x10, 0x8a, 0x2f, 0x1c, 0x6d, 0x9b,
    0x1a, 0x4f, 0x76, 0x7f, 0x01, 0xc0, 0x57, 0x7e);
/* 7e57c002-7f76-4f1a-9b6d-1c2f8a10c001 */
static const ble_uuid128_t RX_UUID = BLE_UUID128_INIT(
    0x01, 0xc0, 0x10, 0x8a, 0x2f, 0x1c, 0x6d, 0x9b,
    0x1a, 0x4f, 0x76, 0x7f, 0x02, 0xc0, 0x57, 0x7e);

typedef struct {
    char id[THREAD_ID_LEN];
    char title[TITLE_LEN];
    char preview[PREVIEW_LEN];
    bool has_agent_message;
    char last_agent_message[LAST_AGENT_MESSAGE_LEN];
} watch_thread_t;

static watch_thread_t *s_threads;
static size_t s_thread_count;
static lv_obj_t *s_connection_label;
static lv_obj_t *s_action_message_label;
static lv_obj_t *s_action_accept_button;
static lv_obj_t *s_action_cancel_button;
static uint8_t s_own_addr_type;
static uint8_t *s_frame_buffer;
static uint8_t s_header[4];
static size_t s_header_received;
static size_t s_frame_expected;
static size_t s_frame_received;
static uint16_t s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint16_t s_tx_val_handle;
static esp_codec_dev_handle_t s_microphone;
static TaskHandle_t s_audio_task_handle;
static TaskHandle_t s_audio_sender_task_handle;
static StreamBufferHandle_t s_audio_stream;
static StaticStreamBuffer_t s_audio_stream_control;
static uint8_t *s_audio_stream_storage;
static volatile bool s_audio_recording;
static volatile bool s_audio_capture_done;
static uint16_t s_audio_sequence;
static volatile TickType_t s_last_activity_tick;
static volatile bool s_display_sleeping;
static volatile bool s_display_wake_requested;
static lv_obj_t *s_sleep_overlay;
static struct attention_motion *s_attention_motion;
static lv_obj_t *s_battery_bar;
static lv_indev_t *s_touch_input;
static lv_obj_t *s_thread_list_obj;
static lv_obj_t *s_response_scroll_obj;
static i2c_master_dev_handle_t s_pmu_device;
static int s_battery_percent = -1;
static bool s_storage_ready;
static bool s_usb_power_present;
static bool s_poweroff_in_progress;
static TickType_t s_display_sleep_started_tick;
static bool s_power_touch_was_pressed;
static TickType_t s_power_last_debug_tick;
static watch_view_t s_current_view = WATCH_VIEW_LIST;
static char s_selected_thread_id[THREAD_ID_LEN];
static int32_t s_restored_list_scroll_y;
static int32_t s_restored_response_scroll_y;
static bool s_restore_scroll_pending;

static uint32_t state_hash_update(uint32_t hash, const void *data, size_t length);
static bool save_watch_state_locked(void);
static bool load_watch_state(void);
static esp_err_t pmu_read(uint8_t reg, uint8_t *data, size_t length);
static esp_err_t pmu_write_byte(uint8_t reg, uint8_t value);
static bool pmu_vbus_present(void);
static esp_err_t pmu_software_shutdown(void);

#if CODEX_WATCH_DIAGNOSTIC
static volatile uint32_t s_diag_orb_frames;
static volatile uint32_t s_diag_audio_reads;
static volatile uint32_t s_diag_audio_packets;
static volatile uint32_t s_diag_flush_started;
static volatile uint32_t s_diag_flush_completed;
static volatile int s_diag_orb_core = -1;
static volatile int s_diag_capture_core = -1;
static volatile int s_diag_sender_core = -1;

/* Called from the LCD panel-IO completion path in the diagnostic image.  It
   intentionally does nothing but an atomic-sized increment: this callback may
   run from the SPI ISR and must never log, allocate, or take a mutex. */
void IRAM_ATTR codex_watch_display_flush_started(void)
{
    s_diag_flush_started++;
}

void IRAM_ATTR codex_watch_display_flush_completed(void)
{
    s_diag_flush_completed++;
}
#endif

static void update_connection_text(const char *text);

static void note_activity(bool request_wake)
{
    s_last_activity_tick = xTaskGetTickCount();
    if (request_wake) s_display_wake_requested = true;
}

static int notify_json(const char *json)
{
    if (s_conn_handle == BLE_HS_CONN_HANDLE_NONE || s_tx_val_handle == 0) return BLE_HS_ENOTCONN;
    const size_t length = strlen(json);
    if (length == 0 || length > 240) return BLE_HS_EINVAL;
    uint8_t frame[244];
    frame[0] = (uint8_t)(length & 0xff);
    frame[1] = (uint8_t)((length >> 8) & 0xff);
    frame[2] = (uint8_t)((length >> 16) & 0xff);
    frame[3] = (uint8_t)((length >> 24) & 0xff);
    memcpy(frame + 4, json, length);
    struct os_mbuf *om = ble_hs_mbuf_from_flat(frame, 4 + length);
    if (!om) return BLE_HS_ENOMEM;
    return ble_gatts_notify_custom(s_conn_handle, s_tx_val_handle, om);
}

static int notify_audio(const uint8_t *data, size_t length, uint16_t sequence)
{
    if (s_conn_handle == BLE_HS_CONN_HANDLE_NONE || s_tx_val_handle == 0) return BLE_HS_ENOTCONN;
    if (length == 0 || length > AUDIO_PACKET_BYTES) return BLE_HS_EINVAL;
    uint8_t frame[4 + 1 + 2 + AUDIO_PACKET_BYTES];
    const size_t payload_length = length + 3;
    frame[0] = (uint8_t)(payload_length & 0xff);
    frame[1] = (uint8_t)((payload_length >> 8) & 0xff);
    frame[2] = (uint8_t)((payload_length >> 16) & 0xff);
    frame[3] = (uint8_t)((payload_length >> 24) & 0xff);
    frame[4] = 0xa0; /* audio PCM packet */
    frame[5] = (uint8_t)(sequence & 0xff);
    frame[6] = (uint8_t)((sequence >> 8) & 0xff);
    memcpy(frame + 7, data, length);
    struct os_mbuf *om = ble_hs_mbuf_from_flat(frame, 4 + payload_length);
    if (!om) return BLE_HS_ENOMEM;
    return ble_gatts_notify_custom(s_conn_handle, s_tx_val_handle, om);
}

static void delete_audio_stream(void)
{
    StreamBufferHandle_t stream = s_audio_stream;
    uint8_t *storage = s_audio_stream_storage;
    s_audio_stream = NULL;
    s_audio_stream_storage = NULL;
    if (stream) vStreamBufferDelete(stream);
    if (storage) heap_caps_free(storage);
}

static void audio_sender_task(void *arg)
{
    (void)arg;
    uint8_t pcm[AUDIO_PACKET_BYTES];
    size_t filled = 0;
    s_audio_sequence = 0;

    while (s_conn_handle != BLE_HS_CONN_HANDLE_NONE) {
        if (filled < sizeof(pcm)) {
            const size_t received = xStreamBufferReceive(
                s_audio_stream,
                pcm + filled,
                sizeof(pcm) - filled,
                pdMS_TO_TICKS(20));
            filled += received;
        }

        const bool stream_empty = xStreamBufferBytesAvailable(s_audio_stream) == 0;
        if (filled < sizeof(pcm) && !(s_audio_capture_done && stream_empty)) continue;
        if (filled == 0 && s_audio_capture_done && stream_empty) break;

        /* PCM16 must always end on a complete sample. */
        filled &= ~(size_t)1;
        if (filled == 0) break;

        int rc;
        const uint16_t sequence = s_audio_sequence;
        do {
            rc = notify_audio(pcm, filled, sequence);
            if (rc == BLE_HS_EBUSY || rc == BLE_HS_EAGAIN) vTaskDelay(pdMS_TO_TICKS(2));
        } while (s_conn_handle != BLE_HS_CONN_HANDLE_NONE &&
                 (rc == BLE_HS_EBUSY || rc == BLE_HS_EAGAIN));
        if (rc != 0) {
            s_audio_recording = false;
            break;
        }
        s_audio_sequence++;
#if CODEX_WATCH_DIAGNOSTIC
        s_diag_audio_packets++;
        s_diag_sender_core = xPortGetCoreID();
#endif
        filled = 0;
        /* A full stream normally keeps this task runnable indefinitely.
           Give the idle task and NimBLE host an explicit scheduling window
           so a long recording cannot trip the task watchdog. */
        vTaskDelay(pdMS_TO_TICKS(1));
    }

    /* The capture task owns the producer side.  Do not free the stream while
       it may still be returning from an I2S read or writing its last block. */
    s_audio_recording = false;
    while (!s_audio_capture_done) vTaskDelay(pdMS_TO_TICKS(5));

    if (s_conn_handle != BLE_HS_CONN_HANDLE_NONE) {
#if !CODEX_WATCH_DIAGNOSTIC
        notify_json("{\"type\":\"audio_end\"}");
#endif
        update_connection_text("Conectado");
    }
    s_audio_sender_task_handle = NULL;
    delete_audio_stream();
    vTaskDelete(NULL);
}

static void audio_task(void *arg)
{
    (void)arg;
    esp_codec_dev_sample_info_t fs = {
        .sample_rate = AUDIO_SAMPLE_RATE,
        .channel = 1,
        .bits_per_sample = 16,
    };
    /* LVGL, NimBLE and the codec fragment internal SRAM before recording.
       Store the 32 KiB PCM queue in the board's 8 MiB PSRAM while keeping
       FreeRTOS' small control structure in internal static memory. */
    s_audio_stream_storage = heap_caps_malloc(
        AUDIO_STREAM_STORAGE_BYTES,
        MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    s_audio_stream = s_audio_stream_storage
        ? xStreamBufferCreateStatic(
            AUDIO_STREAM_BYTES,
            1,
            s_audio_stream_storage,
            &s_audio_stream_control)
        : NULL;
    if (!s_audio_stream) {
        if (s_audio_stream_storage) heap_caps_free(s_audio_stream_storage);
        s_audio_stream_storage = NULL;
        s_audio_recording = false;
        notify_json("{\"type\":\"audio_error\",\"message\":\"No hay memoria para el buffer de audio\"}");
        s_audio_task_handle = NULL;
        vTaskDelete(NULL);
        return;
    }

    if (!s_microphone || esp_codec_dev_open(s_microphone, &fs) != 0) {
        s_audio_recording = false;
        notify_json("{\"type\":\"audio_error\",\"message\":\"No se pudo abrir el microfono\"}");
        delete_audio_stream();
        s_audio_task_handle = NULL;
        vTaskDelete(NULL);
        return;
    }

    uint8_t pcm[AUDIO_CAPTURE_BYTES];
    /* The ES8311 produces a short DC transient after its input path is enabled.
       Discard it, and keep every I2S read aligned to the 4-byte stereo frame
       used internally by esp_codec_dev's mono channel selection. */
    for (int i = 0; i < AUDIO_WARMUP_PACKETS; ++i) {
        if (esp_codec_dev_read(s_microphone, pcm, sizeof(pcm)) != 0) break;
    }

    s_audio_capture_done = false;
#if !CODEX_WATCH_DIAGNOSTIC
    notify_json("{\"type\":\"audio_start\",\"sampleRate\":16000,\"channels\":1,\"bits\":16,\"packetBytes\":232}");
#endif
    update_connection_text("Grabando audio");
    if (xTaskCreatePinnedToCore(audio_sender_task, "audio_ble", 4096, NULL, AUDIO_TASK_PRIORITY,
                                &s_audio_sender_task_handle, AUDIO_SENDER_CORE) != pdPASS) {
        esp_codec_dev_close(s_microphone);
        s_audio_recording = false;
        notify_json("{\"type\":\"audio_error\",\"message\":\"No hay memoria para enviar audio\"}");
        delete_audio_stream();
        s_audio_task_handle = NULL;
        vTaskDelete(NULL);
        return;
    }

    const int64_t started_us = esp_timer_get_time();
    while (s_audio_recording && s_conn_handle != BLE_HS_CONN_HANDLE_NONE &&
           (esp_timer_get_time() - started_us) < ((int64_t)AUDIO_MAX_SECONDS * 1000000LL)) {
        if (esp_codec_dev_read(s_microphone, pcm, sizeof(pcm)) != 0) break;
#if CODEX_WATCH_DIAGNOSTIC
        s_diag_audio_reads++;
        s_diag_capture_core = xPortGetCoreID();
#endif
        size_t sent = 0;
        while (sent < sizeof(pcm) && s_audio_recording &&
               s_conn_handle != BLE_HS_CONN_HANDLE_NONE) {
            sent += xStreamBufferSend(
                s_audio_stream,
                pcm + sent,
                sizeof(pcm) - sent,
                pdMS_TO_TICKS(100));
        }
    }
    esp_codec_dev_close(s_microphone);
    s_audio_recording = false;
    s_audio_capture_done = true;
    s_audio_task_handle = NULL;
    vTaskDelete(NULL);
}

static bool start_audio_recording_task(void)
{
    if (s_audio_recording || s_audio_task_handle != NULL ||
        s_audio_sender_task_handle != NULL ||
        s_conn_handle == BLE_HS_CONN_HANDLE_NONE) {
        return false;
    }
    note_activity(true);
    s_audio_recording = true;
    if (xTaskCreatePinnedToCore(audio_task, "audio_record", 6144, NULL, AUDIO_TASK_PRIORITY,
                                &s_audio_task_handle, AUDIO_CAPTURE_CORE) != pdPASS) {
        s_audio_recording = false;
        return false;
    }
    return true;
}

static void button_task(void *arg)
{
    (void)arg;
    gpio_config_t config = {
        .pin_bit_mask = 1ULL << BOOT_BUTTON_GPIO,
        .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE,
        .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE,
    };
    ESP_ERROR_CHECK(gpio_config(&config));
    int previous = gpio_get_level(BOOT_BUTTON_GPIO);
    while (true) {
        const int current = gpio_get_level(BOOT_BUTTON_GPIO);
        if (previous == 1 && current == 0) {
            vTaskDelay(pdMS_TO_TICKS(40));
            if (gpio_get_level(BOOT_BUTTON_GPIO) == 0) {
                if (s_display_sleeping) {
                    /* The first BOOT press only wakes the watch.  It must not
                       also start a recording while the screen is dark. */
                    note_activity(true);
                } else if (!s_audio_recording && s_audio_task_handle == NULL &&
                    s_audio_sender_task_handle == NULL &&
                    s_conn_handle != BLE_HS_CONN_HANDLE_NONE) {
                    if (!start_audio_recording_task()) {
                        update_connection_text("No hay memoria para grabar");
                    }
                } else if (s_audio_recording) {
                    s_audio_recording = false;
                } else {
                    update_connection_text("Conecta el movil para grabar");
                }
                while (gpio_get_level(BOOT_BUTTON_GPIO) == 0) vTaskDelay(pdMS_TO_TICKS(20));
            }
        }
        previous = current;
        vTaskDelay(pdMS_TO_TICKS(20));
    }
}

static lv_obj_t *active_screen(void)
{
#if LVGL_VERSION_MAJOR >= 9
    return lv_screen_active();
#else
    return lv_scr_act();
#endif
}

static void wake_display_locked(void)
{
    s_display_wake_requested = false;
    note_activity(false);
    if (!s_display_sleeping) return;

    if (s_sleep_overlay) lv_obj_add_flag(s_sleep_overlay, LV_OBJ_FLAG_HIDDEN);
    if (bsp_display_brightness_set(DISPLAY_BRIGHTNESS) == ESP_OK) {
        s_display_sleeping = false;
        s_display_sleep_started_tick = 0;
        ESP_LOGI(TAG, "Display awake");
    }
}

static void power_timer_cb(lv_timer_t *timer)
{
    (void)timer;
    const TickType_t now = xTaskGetTickCount();
    const bool usb_power = pmu_vbus_present();
    if (usb_power != s_usb_power_present) {
        s_usb_power_present = usb_power;
        ESP_LOGI(TAG, "USB power %s", usb_power ? "present (display sleep disabled)"
                                                  : "absent");
        if (usb_power) {
            s_display_wake_requested = true;
        }
    }

    /* Count a touch press once, on its rising edge.  The previous level-based
       check refreshed the idle timer on every 200 ms tick while LVGL reported
       PRESSED.  A stale/latched touch state could therefore keep the display
       awake forever even though the user was no longer touching it. */
    const bool touch_pressed = s_touch_input &&
                               lv_indev_get_state(s_touch_input) ==
                                   LV_INDEV_STATE_PRESSED;
    if (!s_display_sleeping && touch_pressed && !s_power_touch_was_pressed) {
        note_activity(false);
    }
    s_power_touch_was_pressed = touch_pressed;

    /* Keep one low-rate breadcrumb in the normal image.  It makes it possible
       to distinguish USB detection, touch activity, and an active recording
       from a brightness-driver failure when the watch is next connected over
       USB. */
    if (s_power_last_debug_tick == 0 ||
        (now - s_power_last_debug_tick) >= pdMS_TO_TICKS(5000)) {
        const uint32_t idle_ms = (uint32_t)pdTICKS_TO_MS(now - s_last_activity_tick);
        ESP_LOGI(TAG, "Power: usb=%d sleeping=%d idle=%ums touch=%d audio=%d wake=%d",
                 s_usb_power_present, s_display_sleeping, (unsigned)idle_ms,
                 touch_pressed, s_audio_recording, s_display_wake_requested);
        s_power_last_debug_tick = now;
    }

    /* USB is also our development/debug connection.  Leave the AMOLED on
       continuously while VBUS is present and never enter the battery
       shutdown path in this state. */
    if (s_usb_power_present) {
        /* USB itself is already the wake source; do not carry this request
           over into the later USB-disconnect transition and reset the idle
           timer once more. */
        s_display_wake_requested = false;
        if (s_display_sleeping) wake_display_locked();
        s_display_sleep_started_tick = 0;
        return;
    }

    if (s_display_wake_requested || s_audio_recording) {
        wake_display_locked();
        return;
    }

    const TickType_t idle_ticks = xTaskGetTickCount() - s_last_activity_tick;
    if (!s_display_sleeping && idle_ticks >= pdMS_TO_TICKS(DISPLAY_IDLE_TIMEOUT_MS)) {
        if (s_sleep_overlay) {
            lv_obj_remove_flag(s_sleep_overlay, LV_OBJ_FLAG_HIDDEN);
            lv_obj_move_foreground(s_sleep_overlay);
        }
        const esp_err_t brightness_ret = bsp_display_brightness_set(0);
        if (brightness_ret == ESP_OK) {
            s_display_sleeping = true;
            s_display_sleep_started_tick = xTaskGetTickCount();
            ESP_LOGI(TAG, "Display sleeping after %u ms idle", DISPLAY_IDLE_TIMEOUT_MS);
        } else {
            ESP_LOGW(TAG, "Display sleep brightness command failed: %s",
                     esp_err_to_name(brightness_ret));
        }
        return;
    }

    if (s_display_sleeping && s_display_sleep_started_tick != 0 &&
        (xTaskGetTickCount() - s_display_sleep_started_tick) >=
            pdMS_TO_TICKS(FULL_POWER_OFF_AFTER_MS)) {
        if (!save_watch_state_locked()) {
            ESP_LOGW(TAG, "Keeping device awake: could not save watch state");
            s_display_sleep_started_tick = xTaskGetTickCount();
            return;
        }
        s_poweroff_in_progress = true;
        if (s_storage_ready) {
            const esp_err_t unmount_ret = bsp_spiffs_unmount();
            if (unmount_ret != ESP_OK) {
                ESP_LOGW(TAG, "SPIFFS unmount failed: %s", esp_err_to_name(unmount_ret));
            }
            s_storage_ready = false;
        }
        const esp_err_t shutdown_ret = pmu_software_shutdown();
        if (shutdown_ret != ESP_OK) {
            s_poweroff_in_progress = false;
            s_display_sleep_started_tick = xTaskGetTickCount();
            ESP_LOGW(TAG, "AXP2101 software shutdown failed: %s",
                     esp_err_to_name(shutdown_ret));
        } else {
            ESP_LOGI(TAG, "AXP2101 shutdown requested; press PWR to wake");
        }
    }
}

static void power_manager_task(void *arg)
{
    (void)arg;
    while (true) {
        /* Keep power management independent from LVGL's redraw/timer
           scheduling.  The callback itself only uses LVGL while this mutex is
           held, just like the other UI update paths. */
        if (bsp_display_lock(1000)) {
            power_timer_cb(NULL);
            bsp_display_unlock();
        } else {
            ESP_LOGW(TAG, "Power manager could not acquire display lock");
        }
        vTaskDelay(pdMS_TO_TICKS(POWER_POLL_MS));
    }
}

static void init_power_ui(void)
{
    s_last_activity_tick = xTaskGetTickCount();
    s_power_touch_was_pressed = false;
    s_power_last_debug_tick = 0;
    s_touch_input = bsp_display_get_input_dev();

    s_sleep_overlay = lv_obj_create(lv_layer_top());
    lv_obj_remove_style_all(s_sleep_overlay);
    lv_obj_set_pos(s_sleep_overlay, 0, 0);
    lv_obj_set_size(s_sleep_overlay, DISPLAY_WIDTH, DISPLAY_HEIGHT);
    lv_obj_set_style_bg_opa(s_sleep_overlay, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_add_flag(s_sleep_overlay, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_HIDDEN);
    lv_obj_clear_flag(s_sleep_overlay, LV_OBJ_FLAG_SCROLLABLE);

    if (xTaskCreate(power_manager_task, "power_manager", 4096, NULL, 4, NULL) != pdPASS) {
        ESP_LOGE(TAG, "Could not start power manager task");
    }
}

static esp_err_t pmu_read(uint8_t reg, uint8_t *data, size_t length)
{
    if (!s_pmu_device || !data || length == 0) return ESP_ERR_INVALID_STATE;
    return i2c_master_transmit_receive(s_pmu_device, &reg, 1, data, length, 100);
}

static esp_err_t pmu_write_byte(uint8_t reg, uint8_t value)
{
    if (!s_pmu_device) return ESP_ERR_INVALID_STATE;
    const uint8_t data[2] = {reg, value};
    return i2c_master_transmit(s_pmu_device, data, sizeof(data), 100);
}

static bool pmu_vbus_present(void)
{
    uint8_t status = 0;
    /* On this AXP2101 variant STATUS1 bit 5 is the VBUS-good indication.
       STATUS2 bit 3 is VINDPM status, not USB presence; using it made every
       battery-powered boot look like USB was connected and disabled sleep. */
    const bool read_ok = pmu_read(AXP2101_STATUS1_REG, &status, 1) == ESP_OK;
    static int previous_status = -1;
    static int previous_read_ok = -1;
    const int status_for_log = read_ok ? status : -1;
    if (previous_status != status_for_log || previous_read_ok != (int)read_ok) {
        ESP_LOGI(TAG, "AXP STATUS1=0x%02x read=%d VBUS=%d", status, read_ok,
                 read_ok && (status & BIT(5)));
        previous_status = status_for_log;
        previous_read_ok = (int)read_ok;
    }
    return read_ok && (status & BIT(5));
}

static esp_err_t pmu_software_shutdown(void)
{
    uint8_t common_config = 0;
    esp_err_t ret = pmu_read(AXP2101_COMMON_CONFIG_REG, &common_config, 1);
    if (ret != ESP_OK) return ret;
    return pmu_write_byte(AXP2101_COMMON_CONFIG_REG,
                          common_config | AXP2101_COMMON_CONFIG_SOFT_OFF);
}

static uint32_t state_hash_update(uint32_t hash, const void *data, size_t length)
{
    const uint8_t *bytes = (const uint8_t *)data;
    for (size_t i = 0; i < length; ++i) {
        hash ^= bytes[i];
        hash *= 16777619u;
    }
    return hash;
}

static bool read_watch_state_file(const char *path, watch_state_header_t *header_out,
                                  watch_state_meta_t *meta_out, bool apply)
{
    FILE *file = fopen(path, "rb");
    if (!file) return false;

    watch_state_header_t header = {0};
    watch_state_meta_t meta = {0};
    bool valid = false;
    uint8_t scratch[WATCH_STATE_CHUNK_BYTES];
    uint32_t hash = 2166136261u;

    if (fread(&header, 1, sizeof(header), file) != sizeof(header) ||
        header.magic != WATCH_STATE_MAGIC || header.version != WATCH_STATE_VERSION ||
        header.payload_size < sizeof(meta) ||
        header.payload_size > sizeof(meta) + WATCH_STATE_MAX_THREADS * sizeof(watch_thread_t) ||
        fread(&meta, 1, sizeof(meta), file) != sizeof(meta) ||
        meta.view > WATCH_VIEW_CONVERSATION || meta.thread_count > WATCH_STATE_MAX_THREADS ||
        header.payload_size != sizeof(meta) + meta.thread_count * sizeof(watch_thread_t)) {
        fclose(file);
        return false;
    }

    hash = state_hash_update(hash, &meta, sizeof(meta));
    if (apply) {
        if (meta.thread_count > 0 &&
            fread(s_threads, sizeof(watch_thread_t), meta.thread_count, file) != meta.thread_count) {
            fclose(file);
            return false;
        }
        hash = state_hash_update(hash, s_threads, meta.thread_count * sizeof(watch_thread_t));
    } else {
        size_t remaining = meta.thread_count * sizeof(watch_thread_t);
        while (remaining > 0) {
            const size_t chunk = remaining < sizeof(scratch) ? remaining : sizeof(scratch);
            if (fread(scratch, 1, chunk, file) != chunk) {
                fclose(file);
                return false;
            }
            hash = state_hash_update(hash, scratch, chunk);
            remaining -= chunk;
        }
    }

    if (fread(scratch, 1, 1, file) == 0 && feof(file) && hash == header.payload_hash) {
        valid = true;
    }
    fclose(file);

    if (valid) {
        if (header_out) *header_out = header;
        if (meta_out) *meta_out = meta;
    }
    return valid;
}

static bool save_watch_state_locked(void)
{
    if (!s_storage_ready || s_poweroff_in_progress) return false;

    watch_state_meta_t meta = {0};
    meta.view = s_current_view;
    meta.list_scroll_y = s_thread_list_obj ? lv_obj_get_scroll_y(s_thread_list_obj) : 0;
    meta.response_scroll_y = s_response_scroll_obj ? lv_obj_get_scroll_y(s_response_scroll_obj) : 0;
    snprintf(meta.selected_thread_id, sizeof(meta.selected_thread_id), "%s", s_selected_thread_id);
    meta.thread_count = (uint32_t)(s_thread_count <= WATCH_STATE_MAX_THREADS
                                       ? s_thread_count : WATCH_STATE_MAX_THREADS);

    uint32_t last_sequence = 0;
    watch_state_header_t candidate = {0};
    if (read_watch_state_file(WATCH_STATE_PATH_A, &candidate, NULL, false) &&
        candidate.sequence > last_sequence) {
        last_sequence = candidate.sequence;
    }
    if (read_watch_state_file(WATCH_STATE_PATH_B, &candidate, NULL, false) &&
        candidate.sequence > last_sequence) {
        last_sequence = candidate.sequence;
    }

    watch_state_header_t header = {
        .magic = WATCH_STATE_MAGIC,
        .version = WATCH_STATE_VERSION,
        .sequence = last_sequence + 1,
        .payload_size = sizeof(meta) + meta.thread_count * sizeof(watch_thread_t),
        .payload_hash = 2166136261u,
    };
    header.payload_hash = state_hash_update(header.payload_hash, &meta, sizeof(meta));
    header.payload_hash = state_hash_update(
        header.payload_hash, s_threads, meta.thread_count * sizeof(watch_thread_t));

    FILE *file = fopen(WATCH_STATE_PATH_TMP, "wb");
    if (!file) {
        ESP_LOGW(TAG, "Could not open temporary watch state: %s", strerror(errno));
        return false;
    }
    const bool written = fwrite(&header, 1, sizeof(header), file) == sizeof(header) &&
                         fwrite(&meta, 1, sizeof(meta), file) == sizeof(meta) &&
                         fwrite(s_threads, sizeof(watch_thread_t), meta.thread_count, file) ==
                             meta.thread_count &&
                         fflush(file) == 0;
    fclose(file);
    if (!written) {
        remove(WATCH_STATE_PATH_TMP);
        ESP_LOGW(TAG, "Could not write watch state");
        return false;
    }

    const char *target = (header.sequence & 1u) ? WATCH_STATE_PATH_A : WATCH_STATE_PATH_B;
    remove(target);
    if (rename(WATCH_STATE_PATH_TMP, target) != 0) {
        remove(WATCH_STATE_PATH_TMP);
        ESP_LOGW(TAG, "Could not commit watch state: %s", strerror(errno));
        return false;
    }
    ESP_LOGI(TAG, "Saved watch state seq=%u view=%u threads=%u (%u bytes)",
             (unsigned)header.sequence, (unsigned)meta.view, (unsigned)meta.thread_count,
             (unsigned)header.payload_size);
    return true;
}

static bool load_watch_state(void)
{
    if (!s_storage_ready) return false;

    watch_state_header_t a = {0};
    watch_state_header_t b = {0};
    const bool valid_a = read_watch_state_file(WATCH_STATE_PATH_A, &a, NULL, false);
    const bool valid_b = read_watch_state_file(WATCH_STATE_PATH_B, &b, NULL, false);
    const char *path = NULL;
    watch_state_header_t selected = {0};
    if (valid_a && (!valid_b || a.sequence >= b.sequence)) {
        path = WATCH_STATE_PATH_A;
        selected = a;
    } else if (valid_b) {
        path = WATCH_STATE_PATH_B;
        selected = b;
    }
    if (!path || !read_watch_state_file(path, &selected, NULL, true)) return false;

    watch_state_meta_t meta = {0};
    if (!read_watch_state_file(path, NULL, &meta, false)) return false;
    s_thread_count = meta.thread_count;
    s_current_view = (watch_view_t)meta.view;
    snprintf(s_selected_thread_id, sizeof(s_selected_thread_id), "%s", meta.selected_thread_id);
    s_restored_list_scroll_y = meta.list_scroll_y;
    s_restored_response_scroll_y = meta.response_scroll_y;
    s_restore_scroll_pending = true;
    ESP_LOGI(TAG, "Restored watch state seq=%u view=%u threads=%u", (unsigned)selected.sequence,
             (unsigned)meta.view, (unsigned)meta.thread_count);
    return true;
}

static bool read_battery_sample(int *percent, int *millivolts)
{
    uint8_t status = 0;
    uint8_t raw_percent = 0;
    uint8_t voltage[2] = {0};
    if (pmu_read(AXP2101_STATUS1_REG, &status, 1) != ESP_OK || !(status & BIT(3))) {
        return false;
    }
    if (pmu_read(AXP2101_BAT_PERCENT_REG, &raw_percent, 1) != ESP_OK) return false;
    raw_percent &= 0x7f;
    if (raw_percent > 100) return false;

    *percent = raw_percent;
    if (pmu_read(AXP2101_BAT_VOLTAGE_REG, voltage, sizeof(voltage)) == ESP_OK) {
        *millivolts = ((voltage[0] & 0x1f) << 8) | voltage[1];
    } else {
        *millivolts = 0;
    }
    return true;
}

static void update_battery_bar_locked(int percent)
{
    if (!s_battery_bar) return;
    if (percent < 0) {
        lv_obj_add_flag(s_battery_bar, LV_OBJ_FLAG_HIDDEN);
        return;
    }

    const int width = (BATTERY_BAR_MAX_WIDTH * percent + 50) / 100;
    if (width <= 0) {
        lv_obj_add_flag(s_battery_bar, LV_OBJ_FLAG_HIDDEN);
    } else {
        lv_obj_set_width(s_battery_bar, width);
        lv_obj_remove_flag(s_battery_bar, LV_OBJ_FLAG_HIDDEN);
        lv_obj_move_foreground(s_battery_bar);
    }
}

static void battery_task(void *arg)
{
    (void)arg;
    while (true) {
        int raw_percent = -1;
        int millivolts = 0;
        if (read_battery_sample(&raw_percent, &millivolts)) {
            if (s_battery_percent < 0) s_battery_percent = raw_percent;
            else s_battery_percent = (s_battery_percent * 3 + raw_percent + 2) / 4;
            ESP_LOGI(TAG, "Battery raw=%d%% shown=%d%% voltage=%dmV",
                     raw_percent, s_battery_percent, millivolts);
        } else {
            s_battery_percent = -1;
            ESP_LOGW(TAG, "Battery not present or AXP2101 reading unavailable");
        }

        if (bsp_display_lock(1000)) {
            update_battery_bar_locked(s_battery_percent);
            bsp_display_unlock();
        }
        vTaskDelay(pdMS_TO_TICKS(BATTERY_POLL_MS));
    }
}

static void init_battery_monitor(void)
{
    i2c_master_bus_handle_t bus = bsp_i2c_get_handle();
    const i2c_device_config_t config = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address = AXP2101_ADDRESS,
        .scl_speed_hz = 400000,
    };
    esp_err_t ret = i2c_master_bus_add_device(bus, &config, &s_pmu_device);
    if (ret != ESP_OK) {
        ESP_LOGW(TAG, "Could not attach AXP2101: %s", esp_err_to_name(ret));
        return;
    }

    uint8_t chip_id = 0;
    uint8_t adc_ctrl = 0;
    if (pmu_read(AXP2101_CHIP_ID_REG, &chip_id, 1) != ESP_OK) {
        ESP_LOGW(TAG, "AXP2101 did not answer on I2C");
        return;
    }
    if (pmu_read(AXP2101_ADC_CTRL_REG, &adc_ctrl, 1) == ESP_OK) {
        pmu_write_byte(AXP2101_ADC_CTRL_REG, adc_ctrl | BIT(0));
    }
    ESP_LOGI(TAG, "AXP2101 ready (chip id 0x%02x)", chip_id);
    xTaskCreate(battery_task, "battery", 3072, NULL, 2, NULL);
}

static void create_battery_bar(void)
{
    s_battery_bar = lv_obj_create(lv_layer_top());
    lv_obj_remove_style_all(s_battery_bar);
    lv_obj_set_pos(s_battery_bar, BATTERY_BAR_MARGIN, BATTERY_BAR_Y);
    lv_obj_set_size(s_battery_bar, 1, 1);
    lv_obj_set_style_bg_color(s_battery_bar, lv_color_white(), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(s_battery_bar, LV_OPA_COVER, LV_PART_MAIN);
    lv_obj_clear_flag(s_battery_bar, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE);
    lv_obj_add_flag(s_battery_bar, LV_OBJ_FLAG_HIDDEN);
}

static void copy_json_string(char *dst, size_t dst_size, const cJSON *item, const char *fallback)
{
    const char *text = cJSON_IsString(item) && item->valuestring ? item->valuestring : fallback;
    snprintf(dst, dst_size, "%s", text ? text : "");
}

static void parse_thread_json(watch_thread_t *thread, const cJSON *thread_json)
{
    memset(thread, 0, sizeof(*thread));
    copy_json_string(thread->id, sizeof(thread->id),
                     cJSON_GetObjectItemCaseSensitive(thread_json, "id"), "");
    copy_json_string(thread->title, sizeof(thread->title),
                     cJSON_GetObjectItemCaseSensitive(thread_json, "title"), "Sin titulo");
    copy_json_string(thread->preview, sizeof(thread->preview),
                     cJSON_GetObjectItemCaseSensitive(thread_json, "preview"), "");

    const cJSON *messages = cJSON_GetObjectItemCaseSensitive(thread_json, "messages");
    cJSON *message_json;
    cJSON_ArrayForEach(message_json, messages) {
        if (!cJSON_IsObject(message_json)) continue;
        const cJSON *role = cJSON_GetObjectItemCaseSensitive(message_json, "role");
        if (!cJSON_IsString(role) || strcmp(role->valuestring, "assistant") != 0) continue;
        copy_json_string(thread->last_agent_message, sizeof(thread->last_agent_message),
                         cJSON_GetObjectItemCaseSensitive(message_json, "text"), "");
        thread->has_agent_message = thread->last_agent_message[0] != '\0';
    }
}

static int find_thread_index(const char *id)
{
    if (!id || id[0] == '\0') return -1;
    for (size_t i = 0; i < s_thread_count; ++i) {
        if (strcmp(s_threads[i].id, id) == 0) return (int)i;
    }
    return -1;
}

static void style_screen(lv_obj_t *screen)
{
    lv_obj_set_style_bg_color(screen, lv_color_hex(0x090b09), LV_PART_MAIN);
    lv_obj_set_style_text_color(screen, lv_color_hex(0xf3f5ef), LV_PART_MAIN);
    lv_obj_set_style_text_font(screen, &font_montserrat_14_latin, LV_PART_MAIN);
}

static lv_obj_t *make_header(lv_obj_t *screen, const char *title)
{
    lv_obj_t *header = lv_obj_create(screen);
    lv_obj_set_size(header, 368, 66);
    lv_obj_align(header, LV_ALIGN_TOP_MID, 0, 0);
    lv_obj_set_style_bg_color(header, lv_color_hex(0x101410), LV_PART_MAIN);
    lv_obj_set_style_border_width(header, 0, LV_PART_MAIN);
    lv_obj_set_style_radius(header, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_hor(header, 18, LV_PART_MAIN);
    lv_obj_clear_flag(header, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t *label = lv_label_create(header);
    lv_label_set_text(label, title);
    lv_obj_set_style_text_font(label, &font_montserrat_18_latin, LV_PART_MAIN);
    lv_obj_set_style_text_color(label, lv_color_hex(0xf3f5ef), LV_PART_MAIN);
    lv_obj_align(label, LV_ALIGN_LEFT_MID, 0, 0);
    return header;
}

typedef struct {
    lv_timer_t *timer;
    lv_obj_t *blue;
    lv_obj_t *violet;
    lv_obj_t *pink;
    lv_obj_t *cyan;
    uint32_t last_tick;
    float phase;
} orb_motion_t;

static void orb_motion_timer_cb(lv_timer_t *timer)
{
    orb_motion_t *motion = lv_timer_get_user_data(timer);
#if CODEX_WATCH_DIAGNOSTIC
    s_diag_orb_frames++;
    s_diag_orb_core = xPortGetCoreID();
#endif
    if (s_display_sleeping) {
        motion->last_tick = lv_tick_get();
        lv_timer_set_period(timer, 500);
        return;
    }
    /* Moving four translucent layers invalidates most of the orb.  Fifteen
       frames per second while recording is fluid at this size and leaves
       enough CPU for touch, audio capture and BLE. */
    lv_timer_set_period(timer, s_audio_recording ? 66 : 120);
    const uint32_t now = lv_tick_get();
    uint32_t elapsed = now - motion->last_tick;
    motion->last_tick = now;
    if (elapsed > 100) elapsed = 100;

    /* Preserve the phase when recording starts or stops so the speed change
       is immediate but the blobs never jump.  Fast matches the original
       animation; idle is roughly thirteen times slower. */
    motion->phase += (float)elapsed * (s_audio_recording ? 0.0032f : 0.00025f);
    const float phase = motion->phase;

    /* Each blob follows a circular orbit whose radius is smaller than the
       distance between the blob and the outer edge.  Therefore no child can
       escape the round orb and LVGL does not need an expensive ARGB clipping
       layer for every frame. */
    const float violet_phase = phase * 0.82f + 1.3f;
    const float pink_phase = phase * 1.16f + 2.5f;
    const float cyan_phase = phase * 0.69f + 3.4f;
    lv_obj_set_pos(motion->blue,
                   (int32_t)(20.0f + 19.0f * sinf(phase)),
                   (int32_t)(20.0f + 19.0f * cosf(phase)));
    lv_obj_set_pos(motion->violet,
                   (int32_t)(22.0f + 21.0f * cosf(violet_phase)),
                   (int32_t)(22.0f + 21.0f * sinf(violet_phase)));
    lv_obj_set_pos(motion->pink,
                   (int32_t)(24.0f + 23.0f * sinf(pink_phase)),
                   (int32_t)(24.0f + 23.0f * cosf(pink_phase)));
    lv_obj_set_pos(motion->cyan,
                   (int32_t)(26.0f + 25.0f * cosf(cyan_phase)),
                   (int32_t)(26.0f + 25.0f * sinf(cyan_phase)));
}

static void orb_delete_event_cb(lv_event_t *event)
{
    orb_motion_t *motion = lv_event_get_user_data(event);
    if (motion->timer) lv_timer_delete(motion->timer);
    lv_free(motion);
}

static lv_obj_t *make_orb_blob(lv_obj_t *orb, int32_t size, uint32_t color,
                               uint32_t gradient, lv_opa_t opacity)
{
    lv_obj_t *blob = lv_obj_create(orb);
    lv_obj_set_size(blob, size, size);
    lv_obj_set_style_radius(blob, LV_RADIUS_CIRCLE, LV_PART_MAIN);
    lv_obj_set_style_border_width(blob, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(blob, 0, LV_PART_MAIN);
    lv_obj_set_style_bg_color(blob, lv_color_hex(color), LV_PART_MAIN);
    lv_obj_set_style_bg_grad_color(blob, lv_color_hex(gradient), LV_PART_MAIN);
    lv_obj_set_style_bg_grad_dir(blob, LV_GRAD_DIR_VER, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(blob, opacity, LV_PART_MAIN);
    lv_obj_clear_flag(blob, LV_OBJ_FLAG_SCROLLABLE);
    return blob;
}

static void make_animated_orb(lv_obj_t *screen)
{
    lv_obj_t *halo = lv_obj_create(screen);
    lv_obj_set_size(halo, 134, 134);
    lv_obj_align(halo, LV_ALIGN_BOTTOM_MID, 0, -3);
    lv_obj_set_style_radius(halo, LV_RADIUS_CIRCLE, LV_PART_MAIN);
    lv_obj_set_style_bg_color(halo, lv_color_hex(0x10161e), LV_PART_MAIN);
    lv_obj_set_style_border_color(halo, lv_color_hex(0x2d3a48), LV_PART_MAIN);
    lv_obj_set_style_border_width(halo, 1, LV_PART_MAIN);
    lv_obj_set_style_shadow_color(halo, lv_color_hex(0x2b70ff), LV_PART_MAIN);
    lv_obj_set_style_shadow_opa(halo, LV_OPA_30, LV_PART_MAIN);
    lv_obj_set_style_shadow_width(halo, 18, LV_PART_MAIN);
    lv_obj_clear_flag(halo, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t *orb = lv_obj_create(halo);
    lv_obj_set_size(orb, 116, 116);
    lv_obj_center(orb);
    lv_obj_set_style_radius(orb, LV_RADIUS_CIRCLE, LV_PART_MAIN);
    lv_obj_set_style_bg_color(orb, lv_color_hex(0x080b18), LV_PART_MAIN);
    lv_obj_set_style_border_width(orb, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(orb, 0, LV_PART_MAIN);
    /* Do not enable clip_corner here.  On this 116 px translucent object it
       requires a ~54 KiB ARGB layer, nearly the whole 64 KiB LVGL heap. */
    lv_obj_clear_flag(orb, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t *blue = make_orb_blob(orb, 76, 0x249dff, 0x4347ff, LV_OPA_80);
    lv_obj_t *violet = make_orb_blob(orb, 72, 0x8a39ff, 0xd331ff, LV_OPA_80);
    lv_obj_t *pink = make_orb_blob(orb, 68, 0xff327c, 0xff7857, LV_OPA_80);
    lv_obj_t *cyan = make_orb_blob(orb, 64, 0x20e6d0, 0x2c8cff, LV_OPA_70);

    orb_motion_t *motion = lv_malloc(sizeof(*motion));
    if (motion) {
        memset(motion, 0, sizeof(*motion));
        motion->blue = blue;
        motion->violet = violet;
        motion->pink = pink;
        motion->cyan = cyan;
        motion->last_tick = lv_tick_get();
        motion->phase = 0.6f;
        motion->timer = lv_timer_create(orb_motion_timer_cb, 120, motion);
        if (motion->timer) {
            lv_obj_add_event_cb(halo, orb_delete_event_cb, LV_EVENT_DELETE, motion);
            orb_motion_timer_cb(motion->timer);
        } else {
            lv_free(motion);
        }
    }
}

/* Short, non-blocking notification shown when fresh Codex data wakes a
   display that was dimmed for inactivity.  It deliberately uses a few
   opaque/transparent circles instead of shadows or large ARGB layers so the
   alert remains cheap for the QSPI panel while audio/BLE are active. */
typedef struct attention_motion {
    lv_timer_t *timer;
    lv_obj_t *overlay;
    lv_obj_t *rings[3];
    lv_obj_t *label;
    uint32_t started_tick;
} attention_motion_t;

static void attention_stop_locked(void)
{
    attention_motion_t *motion = s_attention_motion;
    if (!motion) return;
    s_attention_motion = NULL;
    if (motion->timer) lv_timer_delete(motion->timer);
    if (motion->overlay) lv_obj_delete(motion->overlay);
    lv_free(motion);
}

static lv_obj_t *make_attention_ring(lv_obj_t *overlay, uint32_t color)
{
    lv_obj_t *ring = lv_obj_create(overlay);
    lv_obj_remove_style_all(ring);
    lv_obj_set_style_radius(ring, LV_RADIUS_CIRCLE, LV_PART_MAIN);
    lv_obj_set_style_bg_opa(ring, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(ring, 5, LV_PART_MAIN);
    lv_obj_set_style_border_color(ring, lv_color_hex(color), LV_PART_MAIN);
    lv_obj_set_style_opa(ring, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_clear_flag(ring, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE);
    return ring;
}

static void attention_timer_cb(lv_timer_t *timer)
{
    attention_motion_t *motion = lv_timer_get_user_data(timer);
    if (!motion || motion != s_attention_motion) {
        lv_timer_delete(timer);
        return;
    }

    const uint32_t elapsed = lv_tick_elaps(motion->started_tick);
    if (elapsed >= 5200) {
        attention_stop_locked();
        return;
    }

    const uint32_t center_x = DISPLAY_WIDTH / 2;
    const uint32_t center_y = 250;
    const uint32_t cycle_ms = 1500;
    for (size_t i = 0; i < 3; ++i) {
        const uint32_t phase_ms = (elapsed + (uint32_t)i * 500) % cycle_ms;
        const float phase = (float)phase_ms / (float)cycle_ms;
        const int32_t size = 64 + (int32_t)(150.0f * phase);
        const lv_opa_t opacity = (lv_opa_t)(210.0f * (1.0f - phase));
        lv_obj_set_size(motion->rings[i], size, size);
        lv_obj_set_pos(motion->rings[i],
                       (int32_t)center_x - size / 2,
                       (int32_t)center_y - size / 2);
        lv_obj_set_style_opa(motion->rings[i], opacity, LV_PART_MAIN);
    }

    /* A gentle badge makes the event legible even when the rings are over a
       busy conversation, while remaining small enough not to hide content. */
    const uint32_t badge_cycle = 900;
    const float badge_phase = (float)(elapsed % badge_cycle) / (float)badge_cycle;
    const float pulse = 0.5f - 0.5f * cosf(badge_phase * 6.2831853f);
    lv_obj_set_style_opa(motion->label,
                         (lv_opa_t)(185.0f + 55.0f * pulse),
                         LV_PART_MAIN);
}

static void show_attention_animation_locked(void)
{
    attention_stop_locked();

    attention_motion_t *motion = lv_malloc(sizeof(*motion));
    if (!motion) return;
    memset(motion, 0, sizeof(*motion));

    motion->overlay = lv_obj_create(lv_layer_top());
    if (!motion->overlay) {
        lv_free(motion);
        return;
    }
    lv_obj_remove_style_all(motion->overlay);
    lv_obj_set_pos(motion->overlay, 0, 0);
    lv_obj_set_size(motion->overlay, DISPLAY_WIDTH, DISPLAY_HEIGHT);
    lv_obj_set_style_bg_opa(motion->overlay, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_clear_flag(motion->overlay, LV_OBJ_FLAG_CLICKABLE | LV_OBJ_FLAG_SCROLLABLE);

    motion->rings[0] = make_attention_ring(motion->overlay, 0x36d1ff);
    motion->rings[1] = make_attention_ring(motion->overlay, 0x8f5bff);
    motion->rings[2] = make_attention_ring(motion->overlay, 0xff477e);

    motion->label = lv_label_create(motion->overlay);
    lv_label_set_text(motion->label, "Nueva respuesta");
    lv_obj_set_style_text_font(motion->label, &font_montserrat_18_latin, LV_PART_MAIN);
    lv_obj_set_style_text_color(motion->label, lv_color_hex(0xf7fbff), LV_PART_MAIN);
    lv_obj_set_style_bg_color(motion->label, lv_color_hex(0x101a2b), LV_PART_MAIN);
    lv_obj_set_style_bg_opa(motion->label, LV_OPA_90, LV_PART_MAIN);
    lv_obj_set_style_radius(motion->label, 18, LV_PART_MAIN);
    lv_obj_set_style_pad_hor(motion->label, 18, LV_PART_MAIN);
    lv_obj_set_style_pad_ver(motion->label, 10, LV_PART_MAIN);
    lv_obj_align(motion->label, LV_ALIGN_TOP_MID, 0, 54);

    motion->started_tick = lv_tick_get();
    motion->timer = lv_timer_create(attention_timer_cb, 40, motion);
    if (!motion->timer) {
        lv_obj_delete(motion->overlay);
        lv_free(motion);
        return;
    }
    s_attention_motion = motion;
    attention_timer_cb(motion->timer);
}

static void show_thread_list(void);

static void back_event_cb(lv_event_t *event)
{
    (void)event;
    show_thread_list();
}

static void action_cancel_event_cb(lv_event_t *event)
{
    (void)event;
    show_thread_list();
}

static void action_accept_event_cb(lv_event_t *event)
{
    (void)event;
    const int rc = notify_json(
        "{\"type\":\"action_response\",\"action\":\"close_codex_desktop\",\"accepted\":true}");
    if (rc == 0) {
        if (s_action_message_label) {
            lv_label_set_text(s_action_message_label, "Cerrando Codex…\nEspera un momento.");
        }
        if (s_action_accept_button) lv_obj_add_state(s_action_accept_button, LV_STATE_DISABLED);
        if (s_action_cancel_button) lv_obj_add_state(s_action_cancel_button, LV_STATE_DISABLED);
    } else if (s_action_message_label) {
        lv_label_set_text(s_action_message_label, "No se pudo enviar la confirmacion al movil.");
    }
}

static void show_desktop_open_prompt(const char *message)
{
    (void)message;
    lv_obj_t *screen = active_screen();
    s_connection_label = NULL;
    s_action_message_label = NULL;
    s_action_accept_button = NULL;
    s_action_cancel_button = NULL;
    s_thread_list_obj = NULL;
    s_response_scroll_obj = NULL;
    lv_obj_clean(screen);
    style_screen(screen);
    lv_obj_clear_flag(screen, LV_OBJ_FLAG_SCROLLABLE);

    s_action_message_label = lv_label_create(screen);
    lv_obj_set_width(s_action_message_label, 320);
    lv_label_set_long_mode(s_action_message_label, LV_LABEL_LONG_WRAP);
    lv_label_set_text(s_action_message_label, "¿Cerrar Codex en el escritorio?");
    lv_obj_set_style_text_color(s_action_message_label, lv_color_hex(0xf3f5ef), LV_PART_MAIN);
    lv_obj_set_style_text_font(s_action_message_label, &font_montserrat_18_latin, LV_PART_MAIN);
    lv_obj_set_style_text_align(s_action_message_label, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    lv_obj_align(s_action_message_label, LV_ALIGN_TOP_MID, 0, 70);

    s_action_accept_button = lv_btn_create(screen);
    lv_obj_set_size(s_action_accept_button, 300, 88);
    lv_obj_align(s_action_accept_button, LV_ALIGN_CENTER, 0, 22);
    lv_obj_set_style_bg_color(s_action_accept_button, lv_color_hex(0x263815), LV_PART_MAIN);
    lv_obj_set_style_border_color(s_action_accept_button, lv_color_hex(0x6e9c38), LV_PART_MAIN);
    lv_obj_set_style_border_width(s_action_accept_button, 2, LV_PART_MAIN);
    lv_obj_set_style_radius(s_action_accept_button, 18, LV_PART_MAIN);
    lv_obj_add_event_cb(s_action_accept_button, action_accept_event_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *accept_label = lv_label_create(s_action_accept_button);
    lv_label_set_text(accept_label, "Cerrar Codex");
    lv_obj_set_style_text_font(accept_label, &font_montserrat_18_latin, LV_PART_MAIN);
    lv_obj_set_style_text_color(accept_label, lv_color_hex(0xf5f7f2), LV_PART_MAIN);
    lv_obj_center(accept_label);

    s_action_cancel_button = lv_btn_create(screen);
    lv_obj_set_size(s_action_cancel_button, 220, 58);
    lv_obj_align(s_action_cancel_button, LV_ALIGN_CENTER, 0, 118);
    lv_obj_set_style_bg_color(s_action_cancel_button, lv_color_hex(0x121712), LV_PART_MAIN);
    lv_obj_set_style_border_color(s_action_cancel_button, lv_color_hex(0x2d362d), LV_PART_MAIN);
    lv_obj_set_style_border_width(s_action_cancel_button, 1, LV_PART_MAIN);
    lv_obj_set_style_radius(s_action_cancel_button, 18, LV_PART_MAIN);
    lv_obj_set_ext_click_area(s_action_cancel_button, 16);
    lv_obj_add_flag(s_action_cancel_button, LV_OBJ_FLAG_PRESS_LOCK);
    lv_obj_add_event_cb(s_action_cancel_button, action_cancel_event_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *cancel_label = lv_label_create(s_action_cancel_button);
    lv_label_set_text(cancel_label, "Cancelar");
    lv_obj_set_style_text_color(cancel_label, lv_color_hex(0xc0c7bd), LV_PART_MAIN);
    lv_obj_center(cancel_label);
}

static void show_action_result(const char *message, bool success)
{
    if (success) {
        show_thread_list();
        if (s_connection_label) {
            lv_label_set_text(s_connection_label, "Codex cerrado");
            lv_obj_set_style_text_color(s_connection_label, lv_color_hex(0xb7f34b), LV_PART_MAIN);
        }
        return;
    }

    lv_obj_t *screen = active_screen();
    s_connection_label = NULL;
    s_action_message_label = NULL;
    s_action_accept_button = NULL;
    s_action_cancel_button = NULL;
    s_thread_list_obj = NULL;
    s_response_scroll_obj = NULL;
    lv_obj_clean(screen);
    style_screen(screen);
    lv_obj_clear_flag(screen, LV_OBJ_FLAG_SCROLLABLE);
    make_header(screen, "No se pudo cerrar");

    lv_obj_t *label = lv_label_create(screen);
    lv_obj_set_width(label, 316);
    lv_label_set_long_mode(label, LV_LABEL_LONG_WRAP);
    lv_label_set_text(label, message);
    lv_obj_set_style_text_align(label, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
    lv_obj_set_style_text_color(label, lv_color_hex(0xff8787), LV_PART_MAIN);
    lv_obj_align(label, LV_ALIGN_CENTER, 0, -30);

    lv_obj_t *back = lv_btn_create(screen);
    lv_obj_set_size(back, 220, 56);
    lv_obj_align(back, LV_ALIGN_BOTTOM_MID, 0, -30);
    lv_obj_set_style_bg_color(back, lv_color_hex(0x2a302a), LV_PART_MAIN);
    lv_obj_set_style_radius(back, 13, LV_PART_MAIN);
    lv_obj_add_event_cb(back, action_cancel_event_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *back_label = lv_label_create(back);
    lv_label_set_text(back_label, "Volver a tareas");
    lv_obj_center(back_label);
}

static void show_conversation(size_t index)
{
    if (index >= s_thread_count) return;
    watch_thread_t *thread = &s_threads[index];
    s_current_view = WATCH_VIEW_CONVERSATION;
    snprintf(s_selected_thread_id, sizeof(s_selected_thread_id), "%s", thread->id);
    s_thread_list_obj = NULL;
    s_response_scroll_obj = NULL;
    lv_obj_t *screen = active_screen();
    s_connection_label = NULL;
    s_action_message_label = NULL;
    s_action_accept_button = NULL;
    s_action_cancel_button = NULL;
    lv_obj_clean(screen);
    style_screen(screen);
    lv_obj_clear_flag(screen, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t *header = make_header(screen, thread->title);
    lv_obj_t *back = lv_btn_create(header);
    lv_obj_set_size(back, 60, 52);
    lv_obj_align(back, LV_ALIGN_LEFT_MID, 0, 0);
    lv_obj_set_style_bg_color(back, lv_color_hex(0x202720), LV_PART_MAIN);
    lv_obj_set_style_radius(back, 11, LV_PART_MAIN);
    lv_obj_set_ext_click_area(back, 12);
    lv_obj_add_flag(back, LV_OBJ_FLAG_PRESS_LOCK);
    lv_obj_add_event_cb(back, back_event_cb, LV_EVENT_CLICKED, NULL);
    lv_obj_t *arrow = lv_label_create(back);
    lv_label_set_text(arrow, LV_SYMBOL_LEFT);
    lv_obj_center(arrow);

    lv_obj_t *title = lv_obj_get_child(header, 0);
    lv_obj_set_width(title, 254);
    lv_label_set_long_mode(title, LV_LABEL_LONG_DOT);
    lv_obj_align(title, LV_ALIGN_LEFT_MID, 72, 0);

    lv_obj_t *response = lv_obj_create(screen);
    lv_obj_set_size(response, 340, 226);
    lv_obj_align(response, LV_ALIGN_TOP_MID, 0, 76);
    lv_obj_set_style_bg_color(response, lv_color_hex(0x121712), LV_PART_MAIN);
    lv_obj_set_style_border_color(response, lv_color_hex(0x2d362d), LV_PART_MAIN);
    lv_obj_set_style_border_width(response, 1, LV_PART_MAIN);
    lv_obj_set_style_radius(response, 18, LV_PART_MAIN);
    lv_obj_set_style_pad_all(response, 17, LV_PART_MAIN);
    lv_obj_set_scroll_dir(response, LV_DIR_VER);
    lv_obj_set_scrollbar_mode(response, LV_SCROLLBAR_MODE_AUTO);
    s_response_scroll_obj = response;

    lv_obj_t *role = lv_label_create(response);
    lv_label_set_text(role, "CODEX");
    lv_obj_set_style_text_color(role, lv_color_hex(0x8ecbff), LV_PART_MAIN);
    lv_obj_set_style_text_font(role, &font_montserrat_14_latin, LV_PART_MAIN);
    lv_obj_align(role, LV_ALIGN_TOP_LEFT, 0, 0);

    lv_obj_t *text = lv_label_create(response);
    lv_obj_set_width(text, 304);
    lv_label_set_long_mode(text, LV_LABEL_LONG_WRAP);
    const char *last = thread->has_agent_message ? thread->last_agent_message : NULL;
    lv_label_set_text(text, last ? last : "Todavía no hay una respuesta de Codex.");
    lv_obj_set_style_text_color(text, lv_color_hex(last ? 0xf5f7f2 : 0x9aa398), LV_PART_MAIN);
    lv_obj_set_style_text_font(text, &font_montserrat_18_latin, LV_PART_MAIN);
    lv_obj_set_style_text_line_space(text, 5, LV_PART_MAIN);
    lv_obj_align_to(text, role, LV_ALIGN_OUT_BOTTOM_LEFT, 0, 10);

    if (s_restore_scroll_pending) {
        lv_obj_scroll_to_y(response, s_restored_response_scroll_y, LV_ANIM_OFF);
        s_restore_scroll_pending = false;
    }

    make_animated_orb(screen);
}

static void thread_event_cb(lv_event_t *event)
{
    const size_t index = (size_t)(uintptr_t)lv_event_get_user_data(event);
    show_conversation(index);
    if (index >= s_thread_count) return;
    char request[160];
    snprintf(request, sizeof(request), "{\"type\":\"thread_request\",\"threadId\":\"%s\"}",
             s_threads[index].id);
    const int rc = notify_json(request);
    if (rc != 0) ESP_LOGW(TAG, "Thread request failed: %d", rc);
}

static void show_thread_list(void)
{
    s_current_view = WATCH_VIEW_LIST;
    s_response_scroll_obj = NULL;
    s_thread_list_obj = NULL;
    lv_obj_t *screen = active_screen();
    s_connection_label = NULL;
    s_action_message_label = NULL;
    s_action_accept_button = NULL;
    s_action_cancel_button = NULL;
    lv_obj_clean(screen);
    style_screen(screen);
    lv_obj_clear_flag(screen, LV_OBJ_FLAG_SCROLLABLE);

    lv_obj_t *header = make_header(screen, "Codex Watch");
    s_connection_label = lv_label_create(header);
    lv_label_set_text(s_connection_label, "BLE listo");
    lv_obj_set_style_text_color(s_connection_label, lv_color_hex(0xb7f34b), LV_PART_MAIN);
    lv_obj_align(s_connection_label, LV_ALIGN_RIGHT_MID, 0, 0);

    lv_obj_t *list = lv_obj_create(screen);
    lv_obj_set_size(list, 368, 382);
    lv_obj_align(list, LV_ALIGN_BOTTOM_MID, 0, 0);
    lv_obj_set_style_bg_opa(list, LV_OPA_TRANSP, LV_PART_MAIN);
    lv_obj_set_style_border_width(list, 0, LV_PART_MAIN);
    lv_obj_set_style_pad_all(list, 12, LV_PART_MAIN);
    lv_obj_set_style_pad_row(list, 9, LV_PART_MAIN);
    lv_obj_set_flex_flow(list, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_scroll_dir(list, LV_DIR_VER);
    lv_obj_set_scrollbar_mode(list, LV_SCROLLBAR_MODE_AUTO);
    s_thread_list_obj = list;

    if (s_thread_count == 0) {
        lv_obj_t *empty = lv_label_create(list);
        lv_obj_set_width(empty, 320);
        lv_label_set_long_mode(empty, LV_LABEL_LONG_WRAP);
        lv_label_set_text(empty, "Esperando datos...\n\nAbre la app Android y conecta con Codex Watch.");
        lv_obj_set_style_text_color(empty, lv_color_hex(0x9aa398), LV_PART_MAIN);
        lv_obj_set_style_text_align(empty, LV_TEXT_ALIGN_CENTER, LV_PART_MAIN);
        if (s_restore_scroll_pending) {
            lv_obj_scroll_to_y(list, s_restored_list_scroll_y, LV_ANIM_OFF);
            s_restore_scroll_pending = false;
        }
        return;
    }

    for (size_t i = 0; i < s_thread_count; ++i) {
        watch_thread_t *thread = &s_threads[i];
        lv_obj_t *card = lv_btn_create(list);
        lv_obj_set_size(card, 334, 76);
        lv_obj_set_style_bg_color(card, lv_color_hex(0x121712), LV_PART_MAIN);
        lv_obj_set_style_border_color(card, lv_color_hex(0x2d362d), LV_PART_MAIN);
        lv_obj_set_style_border_width(card, 1, LV_PART_MAIN);
        lv_obj_set_style_radius(card, 18, LV_PART_MAIN);
        lv_obj_set_style_pad_hor(card, 17, LV_PART_MAIN);
        lv_obj_set_style_pad_ver(card, 12, LV_PART_MAIN);
        lv_obj_add_event_cb(card, thread_event_cb, LV_EVENT_CLICKED, (void *)(uintptr_t)i);

        lv_obj_t *title = lv_label_create(card);
        lv_obj_set_width(title, 300);
        lv_label_set_long_mode(title, LV_LABEL_LONG_DOT);
        lv_label_set_text(title, thread->title);
        lv_obj_set_style_text_color(title, lv_color_hex(0xf5f7f2), LV_PART_MAIN);
        lv_obj_set_style_text_font(title, &font_montserrat_18_latin, LV_PART_MAIN);
        lv_obj_align(title, LV_ALIGN_LEFT_MID, 0, 0);
    }
    if (s_restore_scroll_pending) {
        lv_obj_scroll_to_y(list, s_restored_list_scroll_y, LV_ANIM_OFF);
        s_restore_scroll_pending = false;
    }
}

static void update_connection_text(const char *text)
{
    if (bsp_display_lock(1000)) {
        if (s_connection_label) lv_label_set_text(s_connection_label, text);
        bsp_display_unlock();
    }
}

static bool load_document(const char *json, size_t length)
{
    /* Fresh data counts as activity while the screen is on, but only BOOT is
       allowed to wake a sleeping watch.  Keep the old value so a response
       arriving while the display is dimmed can explicitly alert the user. */
    const bool wake_for_update = s_display_sleeping;
    /* A document received while the display is already asleep is not user
       activity.  The response path below explicitly wakes/alerts the watch;
       keeping the idle timestamp unchanged preserves the five-minute full
       power-off deadline instead of postponing it for every background update. */
    if (!wake_for_update) note_activity(false);
    cJSON *root = cJSON_ParseWithLength(json, length);
    if (!root) {
        ESP_LOGE(TAG, "Invalid JSON document");
        return false;
    }
    const cJSON *action_required = cJSON_GetObjectItemCaseSensitive(root, "actionRequired");
    if (cJSON_IsObject(action_required)) {
        const cJSON *type = cJSON_GetObjectItemCaseSensitive(action_required, "type");
        if (cJSON_IsString(type) && strcmp(type->valuestring, "close_codex_desktop") == 0) {
            char message[320];
            copy_json_string(
                message,
                sizeof(message),
                cJSON_GetObjectItemCaseSensitive(action_required, "message"),
                "Codex Desktop esta abierto. Hay que cerrarlo antes de continuar.");
            cJSON_Delete(root);
            if (bsp_display_lock(2000)) {
                if (wake_for_update) {
                    wake_display_locked();
                    show_attention_animation_locked();
                }
                show_desktop_open_prompt(message);
                bsp_display_unlock();
            }
            return true;
        }
    }

    const cJSON *action_result = cJSON_GetObjectItemCaseSensitive(root, "actionResult");
    if (cJSON_IsObject(action_result)) {
        const cJSON *type = cJSON_GetObjectItemCaseSensitive(action_result, "type");
        if (cJSON_IsString(type) && strcmp(type->valuestring, "close_codex_desktop") == 0) {
            const cJSON *success_json = cJSON_GetObjectItemCaseSensitive(action_result, "success");
            const bool success = cJSON_IsTrue(success_json);
            char message[320];
            copy_json_string(
                message,
                sizeof(message),
                cJSON_GetObjectItemCaseSensitive(action_result, "message"),
                success ? "Codex Desktop se ha cerrado." : "No se pudo cerrar Codex Desktop.");
            cJSON_Delete(root);
            if (bsp_display_lock(2000)) {
                if (wake_for_update) {
                    wake_display_locked();
                    show_attention_animation_locked();
                }
                show_action_result(message, success);
                bsp_display_unlock();
            }
            return true;
        }
    }

    cJSON *threads = cJSON_GetObjectItemCaseSensitive(root, "threads");
    if (!cJSON_IsArray(threads)) {
        cJSON_Delete(root);
        ESP_LOGE(TAG, "JSON has no threads array");
        return false;
    }
    char selected_id[THREAD_ID_LEN] = {0};
    copy_json_string(selected_id, sizeof(selected_id),
                     cJSON_GetObjectItemCaseSensitive(root, "selectedThreadId"), "");

    const bool merge_detail = selected_id[0] != '\0' &&
                              cJSON_GetArraySize(threads) == 1 &&
                              s_thread_count > 0;
    if (!merge_detail) {
        memset(s_threads, 0, MAX_THREADS * sizeof(*s_threads));
        s_thread_count = 0;
    }

    cJSON *thread_json;
    cJSON_ArrayForEach(thread_json, threads) {
        if (!cJSON_IsObject(thread_json)) continue;
        watch_thread_t *thread = NULL;
        if (merge_detail) {
            char incoming_id[THREAD_ID_LEN];
            copy_json_string(incoming_id, sizeof(incoming_id),
                             cJSON_GetObjectItemCaseSensitive(thread_json, "id"), "");
            const int existing = find_thread_index(incoming_id);
            if (existing >= 0) thread = &s_threads[existing];
            else if (s_thread_count < MAX_THREADS) thread = &s_threads[s_thread_count++];
        } else if (s_thread_count < MAX_THREADS) {
            thread = &s_threads[s_thread_count++];
        }
        if (thread) parse_thread_json(thread, thread_json);
    }
    cJSON_Delete(root);

    size_t selected_index = 0;
    bool has_selected = false;
    if (selected_id[0] != '\0') {
        for (size_t i = 0; i < s_thread_count; ++i) {
            if (strcmp(s_threads[i].id, selected_id) == 0) {
                selected_index = i;
                has_selected = true;
                break;
            }
        }
    }
    if (bsp_display_lock(2000)) {
        if (wake_for_update) {
            wake_display_locked();
            show_attention_animation_locked();
        }
        if (has_selected) show_conversation(selected_index);
        else show_thread_list();
        if (s_connection_label) lv_label_set_text(s_connection_label, "Recibido");
        bsp_display_unlock();
    }
    ESP_LOGI(TAG, "%s %u threads", merge_detail ? "Merged detail; retained" : "Loaded",
             (unsigned)s_thread_count);
    return true;
}

static void reset_frame(void)
{
    s_header_received = 0;
    s_frame_expected = 0;
    s_frame_received = 0;
}

static bool consume_bytes(const uint8_t *data, size_t length)
{
    while (length > 0) {
        if (s_frame_expected == 0) {
            size_t needed = sizeof(s_header) - s_header_received;
            size_t take = length < needed ? length : needed;
            memcpy(s_header + s_header_received, data, take);
            s_header_received += take;
            data += take;
            length -= take;
            if (s_header_received < sizeof(s_header)) continue;

            s_frame_expected = (size_t)s_header[0] |
                               ((size_t)s_header[1] << 8) |
                               ((size_t)s_header[2] << 16) |
                               ((size_t)s_header[3] << 24);
            if (s_frame_expected == 0 || s_frame_expected > MAX_FRAME_BYTES) {
                ESP_LOGE(TAG, "Invalid frame length: %u", (unsigned)s_frame_expected);
                reset_frame();
                return false;
            }
        }

        size_t needed = s_frame_expected - s_frame_received;
        size_t take = length < needed ? length : needed;
        memcpy(s_frame_buffer + s_frame_received, data, take);
        s_frame_received += take;
        data += take;
        length -= take;

        if (s_frame_received == s_frame_expected) {
            s_frame_buffer[s_frame_expected] = '\0';
            bool ok = load_document((const char *)s_frame_buffer, s_frame_expected);
            reset_frame();
            if (!ok) return false;
        }
    }
    return true;
}

static int gatt_access(uint16_t conn_handle, uint16_t attr_handle,
                       struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) return BLE_ATT_ERR_READ_NOT_PERMITTED;

    const uint16_t packet_length = OS_MBUF_PKTLEN(ctxt->om);
    if (packet_length == 0 || packet_length > 512) return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    uint8_t packet[512];
    uint16_t copied = 0;
    if (ble_hs_mbuf_to_flat(ctxt->om, packet, sizeof(packet), &copied) != 0) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    return consume_bytes(packet, copied) ? 0 : BLE_ATT_ERR_UNLIKELY;
}

static const struct ble_gatt_svc_def GATT_SERVICES[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &SERVICE_UUID.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {
                .uuid = &RX_UUID.u,
                .val_handle = &s_tx_val_handle,
                .access_cb = gatt_access,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP | BLE_GATT_CHR_F_NOTIFY,
            },
            {0},
        },
    },
    {0},
};

static void advertise(void);

static int gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            s_conn_handle = event->connect.conn_handle;
            ESP_LOGI(TAG, "BLE connected");
            update_connection_text("Conectado");
        } else {
            advertise();
        }
        return 0;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "BLE disconnected");
        s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
        s_audio_recording = false;
        reset_frame();
        update_connection_text("BLE listo");
        advertise();
        return 0;
    case BLE_GAP_EVENT_ADV_COMPLETE:
        advertise();
        return 0;
    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "MTU updated to %u", event->mtu.value);
        return 0;
    default:
        return 0;
    }
}

static void advertise(void)
{
    struct ble_hs_adv_fields fields = {0};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)DEVICE_NAME;
    fields.name_len = strlen(DEVICE_NAME);
    fields.name_is_complete = 1;
    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "Advertising fields failed: %d", rc);
        return;
    }

    struct ble_hs_adv_fields response = {0};
    response.uuids128 = (ble_uuid128_t *)&SERVICE_UUID;
    response.num_uuids128 = 1;
    response.uuids128_is_complete = 1;
    rc = ble_gap_adv_rsp_set_fields(&response);
    if (rc != 0) ESP_LOGW(TAG, "Scan response fields failed: %d", rc);

    struct ble_gap_adv_params params = {0};
    params.conn_mode = BLE_GAP_CONN_MODE_UND;
    params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    rc = ble_gap_adv_start(s_own_addr_type, NULL, BLE_HS_FOREVER, &params, gap_event, NULL);
    if (rc != 0) ESP_LOGE(TAG, "Advertising start failed: %d", rc);
}

static void on_reset(int reason)
{
    ESP_LOGE(TAG, "BLE reset: %d", reason);
}

static void on_sync(void)
{
    int rc = ble_hs_util_ensure_addr(0);
    assert(rc == 0);
    rc = ble_hs_id_infer_auto(0, &s_own_addr_type);
    assert(rc == 0);
    advertise();
    ESP_LOGI(TAG, "Advertising as %s", DEVICE_NAME);
}

static void host_task(void *param)
{
    (void)param;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

#if CODEX_WATCH_DIAGNOSTIC
static void diagnostic_fill_long_message(watch_thread_t *thread)
{
    const char diagnostic_line[] =
        "Mensaje largo de diagnóstico: la pantalla debe seguir respondiendo "
        "mientras se graba audio. ";
    size_t diagnostic_offset = 0;
    while (diagnostic_offset + sizeof(diagnostic_line) <
           sizeof(thread->last_agent_message) - 1) {
        memcpy(thread->last_agent_message + diagnostic_offset,
               diagnostic_line, sizeof(diagnostic_line) - 1);
        diagnostic_offset += sizeof(diagnostic_line) - 1;
        if ((diagnostic_offset / (sizeof(diagnostic_line) - 1)) % 3 == 0) {
            thread->last_agent_message[diagnostic_offset++] = '\n';
        }
    }
    thread->last_agent_message[diagnostic_offset] = '\0';
    thread->has_agent_message = true;
}

static void diagnostic_autostart_task(void *arg)
{
    (void)arg;
    while (s_conn_handle == BLE_HS_CONN_HANDLE_NONE) vTaskDelay(pdMS_TO_TICKS(100));
    /* Let the normal initial document arrive, then force the same detail
       screen a user would be reading before pressing BOOT. */
    vTaskDelay(pdMS_TO_TICKS(3000));
    if (bsp_display_lock(2000)) {
        if (s_thread_count == 0) {
            watch_thread_t *diagnostic = &s_threads[0];
            snprintf(diagnostic->id, sizeof(diagnostic->id), "diagnostic");
            snprintf(diagnostic->title, sizeof(diagnostic->title),
                     "Diagnóstico de grabación");
            s_thread_count = 1;
        }
        diagnostic_fill_long_message(&s_threads[0]);
        show_conversation(0);
        bsp_display_unlock();
    }
    vTaskDelay(pdMS_TO_TICKS(500));
    ESP_LOGI("DIAG", "Starting automatic audio capture");
    if (!start_audio_recording_task()) {
        ESP_LOGE("DIAG", "Could not start automatic audio capture");
        vTaskDelete(NULL);
        return;
    }
    vTaskDelay(pdMS_TO_TICKS(60000));
    s_audio_recording = false;
    ESP_LOGI("DIAG", "Stopped automatic audio capture after 60 seconds");
    vTaskDelete(NULL);
}

static void diagnostic_monitor_task(void *arg)
{
    (void)arg;
    uint32_t previous_orb = 0;
    uint32_t previous_reads = 0;
    uint32_t previous_packets = 0;
    uint32_t previous_flush_started = 0;
    uint32_t previous_flush_completed = 0;
    while (true) {
        vTaskDelay(pdMS_TO_TICKS(1000));
        const uint32_t orb = s_diag_orb_frames;
        const uint32_t reads = s_diag_audio_reads;
        const uint32_t packets = s_diag_audio_packets;
        const uint32_t flush_started = s_diag_flush_started;
        const uint32_t flush_completed = s_diag_flush_completed;
        ESP_LOGI("DIAG",
                 "rec=%d conn=%d orb=%u(+%u,c%d) reads=%u(+%u,c%d) packets=%u(+%u,c%d) flush=%u/%u(+%u/%u) int_heap=%u psram=%u",
                 s_audio_recording, s_conn_handle != BLE_HS_CONN_HANDLE_NONE,
                 (unsigned)orb, (unsigned)(orb - previous_orb), s_diag_orb_core,
                 (unsigned)reads, (unsigned)(reads - previous_reads), s_diag_capture_core,
                 (unsigned)packets, (unsigned)(packets - previous_packets), s_diag_sender_core,
                 (unsigned)flush_started, (unsigned)flush_completed,
                 (unsigned)(flush_started - previous_flush_started),
                 (unsigned)(flush_completed - previous_flush_completed),
                 (unsigned)heap_caps_get_free_size(MALLOC_CAP_INTERNAL | MALLOC_CAP_8BIT),
                 (unsigned)heap_caps_get_free_size(MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
        previous_orb = orb;
        previous_reads = reads;
        previous_packets = packets;
        previous_flush_started = flush_started;
        previous_flush_completed = flush_completed;
    }
}
#endif

void app_main(void)
{
    s_threads = heap_caps_calloc(MAX_THREADS, sizeof(*s_threads), MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    s_frame_buffer = heap_caps_malloc(MAX_FRAME_BYTES + 1, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
    if (!s_threads || !s_frame_buffer) {
        ESP_LOGE(TAG, "Could not allocate conversation storage in PSRAM");
        abort();
    }

    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES || ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        ret = nvs_flash_init();
    }
    ESP_ERROR_CHECK(ret);

    bool restored_state = false;
#if CODEX_WATCH_DIAGNOSTIC
    /* Keep the diagnostic image focused on the audio/display stress test;
       state restoration is exercised by the normal image. */
    (void)restored_state;
    (void)load_watch_state;
#endif
#if !CODEX_WATCH_DIAGNOSTIC
    if (bsp_spiffs_mount() == ESP_OK) {
        s_storage_ready = true;
        restored_state = load_watch_state();
    } else {
        ESP_LOGW(TAG, "Watch state storage unavailable; continuing without persistence");
    }
#endif

    lv_display_t *display = bsp_display_start();
    if (!display) abort();
    ESP_ERROR_CHECK(bsp_display_brightness_set(DISPLAY_BRIGHTNESS));
    if (bsp_display_lock(1000)) {
        create_battery_bar();
        init_power_ui();
#if CODEX_WATCH_DIAGNOSTIC
        watch_thread_t *diagnostic = &s_threads[0];
        snprintf(diagnostic->id, sizeof(diagnostic->id), "diagnostic");
        snprintf(diagnostic->title, sizeof(diagnostic->title), "Diagnóstico de grabación");
        diagnostic->has_agent_message = true;
        /* Exercise the same worst case as a real Codex response: a very long
           wrapped label remains on screen while the orb is invalidated at
           recording rate.  A short message can hide redraw/flush starvation. */
        const char diagnostic_line[] =
            "Mensaje largo de diagnóstico: la pantalla debe seguir respondiendo "
            "mientras se graba audio. ";
        size_t diagnostic_offset = 0;
        while (diagnostic_offset + sizeof(diagnostic_line) <
               sizeof(diagnostic->last_agent_message) - 1) {
            memcpy(diagnostic->last_agent_message + diagnostic_offset,
                   diagnostic_line, sizeof(diagnostic_line) - 1);
            diagnostic_offset += sizeof(diagnostic_line) - 1;
            if ((diagnostic_offset / (sizeof(diagnostic_line) - 1)) % 3 == 0) {
                diagnostic->last_agent_message[diagnostic_offset++] = '\n';
            }
        }
        diagnostic->last_agent_message[diagnostic_offset] = '\0';
        s_thread_count = 1;
        show_conversation(0);
#else
        const int restored_index = restored_state ? find_thread_index(s_selected_thread_id) : -1;
        if (restored_state && s_current_view == WATCH_VIEW_CONVERSATION && restored_index >= 0) {
            show_conversation((size_t)restored_index);
        } else {
            show_thread_list();
        }
#endif
        bsp_display_unlock();
    }
    init_battery_monitor();

    s_microphone = bsp_audio_codec_microphone_init();
    if (!s_microphone) ESP_LOGW(TAG, "Microphone codec unavailable");
    else ESP_ERROR_CHECK(esp_codec_dev_set_in_gain(s_microphone, 30.0));
    xTaskCreate(button_task, "boot_button", 3072, NULL, 4, NULL);

    /* A 16 kHz recording produces roughly 120 BLE notifications per second.
       NimBLE logs each notification at INFO, which floods the USB/UART log
       and can starve the LVGL/display task while recording.  Keep warnings
       and errors, but never emit the per-notification INFO trace. */
    esp_log_level_set("NimBLE", ESP_LOG_WARN);
    ESP_ERROR_CHECK(nimble_port_init());
    ble_hs_cfg.reset_cb = on_reset;
    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;
    ble_svc_gap_init();
    ble_svc_gatt_init();
    assert(ble_gatts_count_cfg(GATT_SERVICES) == 0);
    assert(ble_gatts_add_svcs(GATT_SERVICES) == 0);
    assert(ble_svc_gap_device_name_set(DEVICE_NAME) == 0);
    nimble_port_freertos_init(host_task);
#if CODEX_WATCH_DIAGNOSTIC
    xTaskCreatePinnedToCore(diagnostic_monitor_task, "diag_monitor", 4096, NULL, 6, NULL, 1);
    xTaskCreatePinnedToCore(diagnostic_autostart_task, "diag_start", 3072, NULL, 2, NULL, 0);
#endif
}
