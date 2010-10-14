package com.sputnik.wispr;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.preference.PreferenceManager;
import android.util.Log;

import com.sputnik.wispr.util.FONUtil;

public class NetworkScanReceiver extends BroadcastReceiver {
	private static String TAG = NetworkScanReceiver.class.getName();

	private static long lastCalled = -1;

	private static final int MIN_PERIOD_BTW_CALLS = 10 * 1000;// 10 Seconds

	private static SharedPreferences mPreferences;

	@Override
	public void onReceive(Context context, Intent intent) {
		long now = System.currentTimeMillis();

		// Log.d(TAG, "Action Received: " + intent.getAction() + " From intent: " + intent);

		if (lastCalled == -1 || (now - lastCalled > MIN_PERIOD_BTW_CALLS)) {
			lastCalled = now;
			boolean autoConnectEnabled = getPreferences(context).getBoolean(
					context.getString(R.string.pref_connectionAutoEnable), false);

			if (autoConnectEnabled) {
				WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
				WifiInfo connectionInfo = wm.getConnectionInfo();

				// Log.d(TAG, "connectionInfo.getSupplicantState():" +
				// connectionInfo.getSupplicantState());

				if (connectionInfo.getSupplicantState().equals(SupplicantState.SCANNING)) {
					if (!isAnyPreferedNetworkAvailable(wm)) {
						ScanResult fonScanResult = getFonNetwork(wm.getScanResults());
						if (fonScanResult != null) {
							// Log.d(TAG, "Scan result found:" + fonScanResult);
							WifiConfiguration fonNetwork = lookupConfigurationByScanResult(wm.getConfiguredNetworks(),
									fonScanResult);
							// Log.d(TAG, "FON Network found:" + fonNetwork);
							if (fonNetwork == null) {
								fonNetwork = new WifiConfiguration();
								fonNetwork.BSSID = fonScanResult.BSSID;
								fonNetwork.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
								fonNetwork.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
								fonNetwork.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);

								fonNetwork.networkId = wm.addNetwork(fonNetwork);
								wm.saveConfiguration();
								fonNetwork.SSID = '"' + fonScanResult.SSID + '"';
								int updateNetworkResult = wm.updateNetwork(fonNetwork);
								Log.v(TAG, "New FON Network:" + updateNetworkResult + "::" + fonNetwork);
								if (updateNetworkResult < 0) {
									cleanWiFiConfigurations(wm);
								}
							}

							wm.enableNetwork(fonNetwork.networkId, true);
							lastCalled = System.currentTimeMillis();
							Log.d(TAG, "Trying to connect");
						}// No FON Signal Available
					} else {
						Log.d(TAG, "Not connecting because a prefered network is available");
					}
				}// Not Scanning State
			}
		} else {
			// Log.d(TAG, "Events to close, ignoring.");
		}
	}

	private SharedPreferences getPreferences(Context context) {
		if (mPreferences == null) {
			mPreferences = PreferenceManager.getDefaultSharedPreferences(context);
		}

		return mPreferences;
	}

	private WifiConfiguration lookupConfigurationByScanResult(List<WifiConfiguration> configuredNetworks,
			ScanResult scanResult) {
		boolean found = false;
		WifiConfiguration wifiConfiguration = null;
		Iterator<WifiConfiguration> it = configuredNetworks.iterator();
		while (!found && it.hasNext()) {
			wifiConfiguration = it.next();
			Log.v(TAG, FONUtil.cleanSSID(wifiConfiguration.SSID) + " equals " + FONUtil.cleanSSID(scanResult.SSID));
			if (wifiConfiguration.SSID != null) {
				found = FONUtil.cleanSSID(wifiConfiguration.SSID).equals(FONUtil.cleanSSID(scanResult.SSID));
			}
		}

		if (!found) {
			wifiConfiguration = null;
		}

		return wifiConfiguration;
	}

	private ScanResult getFonNetwork(List<ScanResult> scanResults) {
		ScanResult scanResult = null;
		boolean found = false;

		if (scanResults != null) {
			Iterator<ScanResult> it = scanResults.iterator();
			while (!found && it.hasNext()) {
				scanResult = it.next();
				found = FONUtil.isSupportedNetwork(FONUtil.cleanSSID(scanResult.SSID), scanResult.BSSID);
			}
			if (!found) {
				scanResult = null;
			}
		}

		return scanResult;
	}

	private boolean isAnyPreferedNetworkAvailable(WifiManager wm) {
		Set<String> scanResultsKeys = new HashSet<String>();
		boolean found = false;

		List<WifiConfiguration> configuredNetworks = wm.getConfiguredNetworks();
		if (configuredNetworks != null && !configuredNetworks.isEmpty()) {
			List<ScanResult> scanResults = wm.getScanResults();
			if (scanResults != null && !scanResults.isEmpty()) {
				for (ScanResult scanResult : scanResults) {
					scanResultsKeys.add(FONUtil.cleanSSID(scanResult.SSID));
					Log.v(TAG, "Adding scanResultKey:" + FONUtil.cleanSSID(scanResult.SSID));
				}

				Iterator<WifiConfiguration> it = configuredNetworks.iterator();

				while (!found && it.hasNext()) {
					WifiConfiguration wifiConfiguration = it.next();
					found = scanResultsKeys.contains(FONUtil.cleanSSID(wifiConfiguration.SSID));
					Log.v(TAG, "looking for: " + FONUtil.cleanSSID(wifiConfiguration.SSID)
							+ (found ? " mactch" : " NO match"));
				}
			}
		}

		return found;
	}

	private void cleanWiFiConfigurations(WifiManager wm) {
		List<WifiConfiguration> configuredNetworks = wm.getConfiguredNetworks();
		for (WifiConfiguration wifiConfiguration : configuredNetworks) {
			if (wifiConfiguration.SSID == null) {
				Log.v(TAG, "Removing null wifiConfiguration:" + wifiConfiguration);
				wm.removeNetwork(wifiConfiguration.networkId);
			}
		}
	}

	// Comparator to order Scanresults from high signal level to low
	class ScanResultComparator implements Comparator<ScanResult> {
		public int compare(ScanResult scanResult1, ScanResult scanResult2) {
			return scanResult2.level - scanResult1.level;
		}
	}
}