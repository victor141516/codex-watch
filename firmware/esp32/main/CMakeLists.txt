idf_component_register(
    SRCS
        "codex_watch_main.c"
        "font_montserrat_14_latin.c"
        "font_montserrat_18_latin.c"
    INCLUDE_DIRS "."
    REQUIRES esp32_s3_touch_amoled_1_8 bt nvs_flash json esp_driver_i2c
)

target_compile_options(${COMPONENT_LIB} PRIVATE -Wno-error=shift-count-negative)

if(CODEX_WATCH_DIAGNOSTIC)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE CODEX_WATCH_DIAGNOSTIC=1)
else()
    target_compile_definitions(${COMPONENT_LIB} PRIVATE CODEX_WATCH_DIAGNOSTIC=0)
endif()

idf_component_get_property(LVGL_LIB lvgl__lvgl COMPONENT_LIB)
if(TARGET ${LVGL_LIB})
    target_compile_options(${LVGL_LIB} PRIVATE -Wno-error=attributes)
endif()
