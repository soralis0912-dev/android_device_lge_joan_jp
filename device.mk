#
# Copyright (C) 2018-2024 The LineageOS Project
#
# SPDX-License-Identifier: Apache-2.0
#

DEVICE_PATH := device/lge/joan_jp

# Inherit common repository
$(call inherit-product, device/lge/joan-common/joan-common.mk)

# NFC
PRODUCT_PACKAGES += \
	libhidlbase_shim

# The HAL service is `disabled` in its own init script and starts on nothing
# but this property, which the bootloader does not hand out - stock carries it
# in /system/build.prop. Without it the CXD224x never comes up, and the only
# symptom is NFC quietly not existing. The two japanese SKUs are the Sony part;
# h930 and the rest are NXP, so this cannot live in joan-common.
PRODUCT_PRODUCT_PROPERTIES += \
	ro.boot.vendor.lge.nfc.vendor=sony

# FeliCa (Osaifu-Keitai). All prebuilt java - no boot jar, no resource library.
# These are the japanese SKUs' own, which is why they are here rather than in
# joan-common.
#
# MobileFeliCaClient will not start without the "felica" binder service, which
# on stock comes from LG's fork of packages/apps/Nfc. Here it is its own app,
# packages/apps/FelicaService, which forwards the element calls to the half
# that has to stay inside the nfc process. See docs/felica-port.md.
# The xml and cfg files are not listed here: extract_utils emits those as
# PRODUCT_COPY_FILES in joan_jp-vendor.mk, not as modules, so naming them
# only get them rejected as non-existent.
$(call inherit-product, packages/apps/FelicaService/device.mk)

# The stock HAL config with the listen mode route ids rewritten - the header
# in the file says why. Extracting it instead would put stock's back.
PRODUCT_COPY_FILES += \
	$(DEVICE_PATH)/nfc/libnfc-cxd22xx.conf:$(TARGET_COPY_OUT_VENDOR)/etc/libnfc-cxd22xx.conf

# The secure element is provisioned per model and one image serves both
# japanese handsets, so the configuration is per SKU and init.joan.rc binds the
# matching set at early-init. FelicaParts lets that be overridden by hand, for
# a handset whose element was provisioned as the other model - see
# rootdir/system_ext/etc/init/init.felica_model.rc.
PRODUCT_PACKAGES += \
	FelicaParts

PRODUCT_COPY_FILES += \
	$(DEVICE_PATH)/rootdir/system_ext/etc/init/init.felica_model.rc:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/init/init.felica_model.rc

PRODUCT_PACKAGES += \
	MobileFeliCaClient \
	MobileFeliCaMenuMainApp \
	MobileFeliCaMenuApp \
	MobileFeliCaSettingApp \
	MobileFeliCaWebPlugin \
	MobileFeliCaWebPluginBoot \
	com.felicanetworks.felica \
	com.felicanetworks.felicaextra

PRODUCT_SOONG_NAMESPACES += \
    $(DEVICE_PATH)

# Inherit proprietary blobs
$(call inherit-product, vendor/lge/joan_jp/joan_jp-vendor.mk)

# TV
PRODUCT_PACKAGES += \
    JoanTv
