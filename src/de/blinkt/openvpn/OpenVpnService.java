/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.blinkt.openvpn;import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Vector;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.Handler.Callback;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import de.blinkt.openvpn.OpenVPN.ByteCountListener;
import de.blinkt.openvpn.OpenVPN.StateListener;

public class OpenVpnService extends VpnService implements StateListener, Callback, ByteCountListener {
	public static final String START_SERVICE = "de.blinkt.openvpn.START_SERVICE";
	public static final String START_SERVICE_STICKY = "de.blinkt.openvpn.START_SERVICE_STICKY";
	public static final String ALWAYS_SHOW_NOTIFICATION = "de.blinkt.openvpn.NOTIFICATION_ALWAYS_VISIBLE";


	private Thread mProcessThread=null;

	private Vector<String> mDnslist=new Vector<String>();

	private VpnProfile mProfile;

	private String mDomain=null;

	private Vector<CIDRIP> mRoutes=new Vector<CIDRIP>();
	private Vector<String> mRoutesv6=new Vector<String>();

	private CIDRIP mLocalIP=null;

	private OpenVpnManagementThread mManagementThread;

	private Thread mSocketManagerThread;
	private int mMtu;
	private String mLocalIPv6=null;
	private NetworkSateReceiver mNetworkStateReceiver;

	private boolean mDisplayBytecount=false;

	private boolean mStarting=false;

	private long mConnecttime;


	private static final int OPENVPN_STATUS = 1;

	public static final int PROTECT_FD = 0;


	private static final int LEVEL_OFFLINE = 0;
	private static final int LEVEL_NOTCONNECTED = 1;
	private static final int LEVEL_CONNECTED = 2;

	private static boolean mNotificationalwaysVisible=false;

	private final IBinder mBinder = new LocalBinder();

	public class LocalBinder extends Binder {
		public OpenVpnService getService() {
			// Return this instance of LocalService so clients can call public methods
			return OpenVpnService.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		String action = intent.getAction();
		if( action !=null && action.equals(START_SERVICE))
			return mBinder;
		else
			return super.onBind(intent);
	}

	@Override
	public void onRevoke() {
		OpenVpnManagementThread.stopOpenVPN();
		endVpnService();
	}

	// Similar to revoke but do not try to stop process
	public void processDied() {
		endVpnService();
	}

	private void endVpnService() {
		mProcessThread=null;
		OpenVPN.logBuilderConfig(null);
		OpenVPN.removeStateListener(this);
		OpenVPN.removeByteCountListener(this);
		ProfileManager.setConntectedVpnProfileDisconnected(this);
		if(!mStarting) {
			stopForeground(!mNotificationalwaysVisible);

			if( !mNotificationalwaysVisible)
				stopSelf();
		}
	}

	private void showNotification(String msg, String tickerText, boolean lowpriority, long when, int level) {
		String ns = Context.NOTIFICATION_SERVICE;
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(ns);


		int icon = R.drawable.notification_icon;
		android.app.Notification.Builder nbuilder = new Notification.Builder(this);

		if(mProfile!=null)
			nbuilder.setContentTitle(getString(R.string.notifcation_title,mProfile.mName));
		else
			nbuilder.setContentTitle(getString(R.string.notifcation_title_notconnect));

		nbuilder.setContentText(msg);
		nbuilder.setOnlyAlertOnce(true);
		nbuilder.setOngoing(true);
		nbuilder.setContentIntent(getLogPendingIntent());
		nbuilder.setSmallIcon(icon,level);
		if(when !=0)
			nbuilder.setWhen(when);


		// Try to set the priority available since API 16 (Jellybean)
		jbNotificationExtras(lowpriority, nbuilder);
		if(tickerText!=null && !tickerText.equals(""))
			nbuilder.setTicker(tickerText);

		@SuppressWarnings("deprecation")
		Notification notification = nbuilder.getNotification();


		mNotificationManager.notify(OPENVPN_STATUS, notification);
		startForeground(OPENVPN_STATUS, notification);
	}


	@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
	private void jbNotificationExtras(boolean lowpriority,
			android.app.Notification.Builder nbuilder) {
		try {
			if(lowpriority) {
				Method setpriority = nbuilder.getClass().getMethod("setPriority", int.class);
				// PRIORITY_MIN == -2
				setpriority.invoke(nbuilder, -2 );

				Method setUsesChronometer = nbuilder.getClass().getMethod("setPriority", boolean.class);
				setUsesChronometer.invoke(nbuilder,true);
				
				/*				PendingIntent cancelconnet=null;

				nbuilder.addAction(android.R.drawable.ic_menu_close_clear_cancel, 
						getString(R.string.cancel_connection),cancelconnet); */
			}

			//ignore exception
		} catch (NoSuchMethodException nsm) {
		} catch (IllegalArgumentException e) {
		} catch (IllegalAccessException e) {
		} catch (InvocationTargetException e) {
		}

	}

	PendingIntent getLogPendingIntent() {
		// Let the configure Button show the Log
		Intent intent = new Intent(getBaseContext(),LogWindow.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
		PendingIntent startLW = PendingIntent.getActivity(getApplicationContext(), 0, intent, 0);
		intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
		return startLW;

	}


	private LocalServerSocket openManagmentInterface(int tries) {
		// Could take a while to open connection
		String socketname = (getCacheDir().getAbsolutePath() + "/" +  "mgmtsocket");
		// The sock is transfered to the LocalServerSocket, ignore warning
		@SuppressWarnings("resource")
		LocalSocket sock = new LocalSocket();

		while(tries > 0 && !sock.isConnected()) {
			try {
				sock.bind(new LocalSocketAddress(socketname,
						LocalSocketAddress.Namespace.FILESYSTEM));
			} catch (IOException e) {
				// wait 300 ms before retrying
				try { Thread.sleep(300);
				} catch (InterruptedException e1) {}

			} 
			tries--;
		}

		try {
			LocalServerSocket lss = new LocalServerSocket(sock.getFileDescriptor());
			return lss;
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;


	}

	void registerNetworkStateReceiver() {
		// Registers BroadcastReceiver to track network connection changes.
		IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
		mNetworkStateReceiver = new NetworkSateReceiver(mManagementThread);
		this.registerReceiver(mNetworkStateReceiver, filter);
	}


	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {

		if(intent != null && intent.getBooleanExtra(ALWAYS_SHOW_NOTIFICATION, false))
			mNotificationalwaysVisible=true;

		OpenVPN.addStateListener(this);
		OpenVPN.addByteCountListener(this);

		if(intent != null && intent.getAction() !=null &&intent.getAction().equals(START_SERVICE))
			return START_NOT_STICKY;
		if(intent != null && intent.getAction() !=null &&intent.getAction().equals(START_SERVICE_STICKY)) {
			return START_REDELIVER_INTENT;
		}


		// Extract information from the intent.
		String prefix = getPackageName();
		String[] argv = intent.getStringArrayExtra(prefix + ".ARGV");
		String nativelibdir = intent.getStringExtra(prefix + ".nativelib");
		String profileUUID = intent.getStringExtra(prefix + ".profileUUID");

		mProfile = ProfileManager.get(profileUUID);

		showNotification("Starting VPN " + mProfile.mName,"Starting VPN " + mProfile.mName, false,0,LEVEL_NOTCONNECTED);



		// Set a flag that we are starting a new VPN
		mStarting=true;
		// Stop the previous session by interrupting the thread.
		if(OpenVpnManagementThread.stopOpenVPN()){
			// an old was asked to exit, wait 2s
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}
		}

		if (mProcessThread!=null) {
			mProcessThread.interrupt();
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
			}
		}
		// An old running VPN should now be exited
		mStarting=false;


		// Open the Management Interface
		LocalServerSocket mgmtsocket = openManagmentInterface(8);

		if(mgmtsocket!=null) {
			// start a Thread that handles incoming messages of the managment socket
			mManagementThread=new OpenVpnManagementThread(mProfile,mgmtsocket,this);
			mSocketManagerThread = new Thread(mManagementThread,"OpenVPNMgmtThread");
			mSocketManagerThread.start();
			OpenVPN.logInfo("started Socket Thread");
			registerNetworkStateReceiver();
		}


		// Start a new session by creating a new thread.
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);        

		boolean ovpn3 = prefs.getBoolean("ovpn3", false);

		Runnable processThread;
		if(ovpn3) {

			OpenVPNThreadv3 v3Thread = new OpenVPNThreadv3(this,mProfile);
			
			processThread = v3Thread;

		} else {

			processThread = new OpenVPNThread(this, argv,nativelibdir);

		}
		mProcessThread = new Thread(processThread, "OpenVPNProcessThread");
		mProcessThread.start();

		ProfileManager.setConnectedVpnProfile(this, mProfile);

		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy() {
		if (mProcessThread != null) {
			mManagementThread.managmentCommand("signal SIGINT\n");

			mProcessThread.interrupt();
		}
		if (mNetworkStateReceiver!= null) {
			this.unregisterReceiver(mNetworkStateReceiver);
		}

	}



	public ParcelFileDescriptor openTun() {
		Builder builder = new Builder();

		if(mLocalIP==null && mLocalIPv6==null) {
			OpenVPN.logMessage(0, "", getString(R.string.opentun_no_ipaddr));
			return null;
		}

		if(mLocalIP!=null) {
			builder.addAddress(mLocalIP.mIp, mLocalIP.len);
		}

		if(mLocalIPv6!=null) {
			String[] ipv6parts = mLocalIPv6.split("/");
			builder.addAddress(ipv6parts[0],Integer.parseInt(ipv6parts[1]));
		}


		for (String dns : mDnslist ) {
			try {
				builder.addDnsServer(dns);
			} catch (IllegalArgumentException iae) {
				OpenVPN.logError(R.string.dns_add_error, dns,iae.getLocalizedMessage());
			}
		}


		builder.setMtu(mMtu);


		for (CIDRIP route:mRoutes) {
			try {
				builder.addRoute(route.mIp, route.len);
			} catch (IllegalArgumentException ia) {
				OpenVPN.logMessage(0, "", getString(R.string.route_rejected) + route + " " + ia.getLocalizedMessage());
			}
		}

		for(String v6route:mRoutesv6) {
			try {
				String[] v6parts = v6route.split("/");
				builder.addRoute(v6parts[0],Integer.parseInt(v6parts[1]));
			} catch (IllegalArgumentException ia) {
				OpenVPN.logMessage(0, "", getString(R.string.route_rejected) + v6route + " " + ia.getLocalizedMessage());
			}
		}

		if(mDomain!=null)
			builder.addSearchDomain(mDomain);

		String bconfig[] = new String[6];

		bconfig[0]=  getString(R.string.last_openvpn_tun_config);
		bconfig[1] = getString(R.string.local_ip_info,mLocalIP.mIp,mLocalIP.len,mLocalIPv6, mMtu);
		bconfig[2] = getString(R.string.dns_server_info, joinString(mDnslist));
		bconfig[3] = getString(R.string.dns_domain_info, mDomain);
		bconfig[4] = getString(R.string.routes_info, joinString(mRoutes));
		bconfig[5] = getString(R.string.routes_info6, joinString(mRoutesv6));

		String session = mProfile.mName;
		if(mLocalIP!=null && mLocalIPv6!=null)
			session = getString(R.string.session_ipv6string,session, mLocalIP, mLocalIPv6);
		else if (mLocalIP !=null)
			session= getString(R.string.session_ipv4string, session, mLocalIP);

		builder.setSession(session);


		OpenVPN.logBuilderConfig(bconfig);

		// No DNS Server, log a warning 
		if(mDnslist.size()==0)
			OpenVPN.logInfo(R.string.warn_no_dns);

		// Reset information
		mDnslist.clear();
		mRoutes.clear();
		mRoutesv6.clear();
		mLocalIP=null;
		mLocalIPv6=null;
		mDomain=null;

		builder.setConfigureIntent(getLogPendingIntent());

		try {
			ParcelFileDescriptor pfd = builder.establish();
			return pfd;
		} catch (Exception e) {
			OpenVPN.logMessage(0, "", getString(R.string.tun_open_error));
			OpenVPN.logMessage(0, "", getString(R.string.error) + e.getLocalizedMessage());
			OpenVPN.logMessage(0, "", getString(R.string.tun_error_helpful));
			return null;
		}

	}


	// Ugly, but java has no such method
	private <T> String joinString(Vector<T> vec) {
		String ret = "";
		if(vec.size() > 0){ 
			ret = vec.get(0).toString();
			for(int i=1;i < vec.size();i++) {
				ret = ret + ", " + vec.get(i).toString();
			}
		}
		return ret;
	}






	public void addDNS(String dns) {
		mDnslist.add(dns);		
	}


	public void setDomain(String domain) {
		if(mDomain==null) {
			mDomain=domain;
		}
	}


	public void addRoute(CIDRIP route)
	{
		mRoutes.add(route );
	}
	public void addRoute(String dest, String mask) {
		CIDRIP route = new CIDRIP(dest, mask);		
		if(route.len == 32 && !mask.equals("255.255.255.255")) {
			OpenVPN.logMessage(0, "", getString(R.string.route_not_cidr,dest,mask));
		}

		if(route.normalise())
			OpenVPN.logMessage(0, "", getString(R.string.route_not_netip,dest,route.len,route.mIp));

		mRoutes.add(route);
	}

	public void addRoutev6(String extra) {
		mRoutesv6.add(extra);		
	}

	public void setMtu(int mtu) {
		mMtu=mtu;
	}
	
	public void setLocalIP(CIDRIP cdrip)
	{
		mLocalIP=cdrip;
	}
	
	
	public void setLocalIP(String local, String netmask,int mtu, String mode) {
		mLocalIP = new CIDRIP(local, netmask);
		mMtu = mtu;

		if(mLocalIP.len == 32 && !netmask.equals("255.255.255.255")) {
			// get the netmask as IP
			long netint = CIDRIP.getInt(netmask);
			if(Math.abs(netint - mLocalIP.getInt()) ==1) {
				if("net30".equals(mode))
					mLocalIP.len=30;
				else
					mLocalIP.len=31;
			} else {
				OpenVPN.logMessage(0, "", getString(R.string.ip_not_cidr, local,netmask,mode));
			}
		}
	}

	public void setLocalIPv6(String ipv6addr) {
		mLocalIPv6 = ipv6addr;
	}

	@Override
	public void updateState(String state,String logmessage, int resid) {
		// If the process is not running, ignore any state, 
		// Notification should be invisible in this state
		if(mProcessThread==null && !mNotificationalwaysVisible)
			return;

		// Display byte count only after being connected

		{
			int level;
			if("CONNECTED".equals(state)) {
				mDisplayBytecount = true;
				mConnecttime = System.currentTimeMillis();
				level = LEVEL_CONNECTED;
			} else {
				if ("NONETWORK".equals(state)) {
					level = LEVEL_OFFLINE;
				} else {
					level = LEVEL_NOTCONNECTED;
				}
				mDisplayBytecount = false;
			}

			// Other notifications are shown,
			// This also mean we are no longer connected, ignore bytecount messages until next
			// CONNECTED
			String ticker = getString(resid);
			showNotification(getString(resid) +" " + logmessage,ticker,false,0, level);

		}
	}

	@Override
	public void updateByteCount(long in, long out, long diffin, long diffout) {
		if(mDisplayBytecount) {
			String netstat = String.format(getString(R.string.statusline_bytecount),
					humanReadableByteCount(in, false),
					humanReadableByteCount(diffin, false),
					humanReadableByteCount(out, false),
					humanReadableByteCount(diffout, false));

			boolean lowpriority = !mNotificationalwaysVisible;
			showNotification(netstat,null,lowpriority,mConnecttime, LEVEL_CONNECTED);
		}

	}

	// From: http://stackoverflow.com/questions/3758606/how-to-convert-byte-size-into-human-readable-format-in-java
	public static String humanReadableByteCount(long bytes, boolean si) {
		int unit = si ? 1000 : 1024;
		if (bytes < unit) return bytes + " B";
		int exp = (int) (Math.log(bytes) / Math.log(unit));
		String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp-1) + (si ? "" : "i");
		return String.format(Locale.getDefault(),"%.1f %sB", bytes / Math.pow(unit, exp), pre);
	}

	@Override
	public boolean handleMessage(Message msg) {
		Runnable r = msg.getCallback();
		if(r!=null){
			r.run();
			return true;
		} else {
			return false;
		}
	}

	public OpenVpnManagementThread getManagementThread() {
		return mManagementThread;
	}
}
