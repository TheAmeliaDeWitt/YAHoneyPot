package com.marchnetworks.common.types;

public enum DeviceExceptionTypes
{
	UNKNOWN,
	NO_AVAILABLE_LICENSE_RECORDER,
	NO_AVAILABLE_LICENSE_CHANNEL,
	COULD_NOT_ASSIGN_SOFT_LICENSE,
	SOFT_LICENSE_EXPIRED,
	LICENSE_STATE_FROZEN,
	DEVICE_REGISTERED_WITH_ANOTHER_SERVER,
	DEVICE_ALREADY_REGISTERED_WITH_THIS_SERVER,
	DEVICE_SESSION_REQUEST_ERROR,
	NOT_AUTHORIZED,
	BUSY_UNAVAILABLE,
	COMMUNICATION_TIMEOUT,
	INVALID_DEVICE_SUBSCRIPTION,
	DEVICE_NOT_FOUND,
	NOT_A_COMPOSITE_DEVICE,
	INVALID_DEVICE_ADDRESS,
	FUNCTION_NOT_IMPLEMENTED,
	FEATURE_NOT_SUPPORTED,
	STATION_ID_ALREADY_EXISTS,
	DEVICE_NOT_MARKED_FOR_REPLACEMENT,
	DEVICE_FIRMWARE_VERSION_TOO_LOW,
	DEVICE_REPLACE_CONFIG_APPLY_ERROR,
	DEVICE_REPLACE_MODEL_ERROR,
	DEVICE_REPLACE_RETRY_FAIL,
	DEVICE_REPLACE_UNKNOWN_ERROR,
	DEVICE_CONFIGURATION_SNAPSHOT_NOT_EXISTS,
	DEVICE_OFFLINE,
	DEVICE_VERSION_NOT_SUPPORTED,
	DEVICE_CERTIFICATE_NOT_TRUSTED;

	private DeviceExceptionTypes()
	{
	}
}