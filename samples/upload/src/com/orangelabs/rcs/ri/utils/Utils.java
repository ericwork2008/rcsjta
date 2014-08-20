/*******************************************************************************
 * Software Name : RCS IMS Stack
 *
 * Copyright (C) 2010 France Telecom S.A.
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
 ******************************************************************************/
package com.orangelabs.rcs.ri.utils;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.DateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Set;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.widget.Toast;

import com.orangelabs.rcs.ri.upload.R;

/**
 * Utility functions
 * 
 * @author Jean-Marc AUFFRET
 */
public class Utils {

	/**
	 * Returns the application version from manifest file 
	 * 
	 * @param ctx Context
	 * @return Application version or null if not found
	 */
	public static String getApplicationVersion(Context ctx) {
		String version = null;
		try {
			PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
			version = info.versionName;
		} catch(NameNotFoundException e) {
		}
		return version;
	}
	
	/**
	 * Display a toast
	 * 
	 * @param ctx Context
	 * @param message Message to be displayed
	 */
    public static void displayToast(Context ctx, String message) {
        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
    }

	/**
	 * Display a long toast
	 * 
	 * @param ctx Context
	 * @param message Message to be displayed
	 */
    public static void displayLongToast(Context ctx, String message) {
        Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
    }
    
    /**
	 * Show a message and exit activity
	 * 
	 * @param activity Activity
	 * @param msg Message to be displayed
	 */
    public static void showMessageAndExit(final Activity activity, String msg) {
        if (activity.isFinishing()) {
        	return;
        }

		AlertDialog.Builder builder = new AlertDialog.Builder(activity);
		builder.setMessage(msg);
		builder.setTitle(R.string.title_msg);
		builder.setCancelable(false);
		builder.setPositiveButton(activity.getString(R.string.label_ok),
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						activity.finish();
					}
				});
		AlertDialog alert = builder.create();
		alert.show();
    }

	/**
	 * Show an message
	 * 
	 * @param activity Activity
	 * @param msg Message to be displayed
	 * @return Dialog
	 */
    public static AlertDialog showMessage(Activity activity, String msg) {
    	AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    	builder.setMessage(msg);
    	builder.setTitle(R.string.title_msg);
    	builder.setCancelable(false);
    	builder.setPositiveButton(activity.getString(R.string.label_ok), null);
    	AlertDialog alert = builder.create();
    	alert.show();
		return alert;
    }

    /**
	 * Show a message with a specific title
	 * 
	 * @param activity Activity
	 * @param title Title of the dialog
	 * @param msg Message to be displayed
	 * @return Dialog
	 */
    public static AlertDialog showMessage(Activity activity, String title, String msg) {
    	AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    	builder.setMessage(msg);
    	builder.setTitle(title);
    	builder.setCancelable(false);
    	builder.setPositiveButton(activity.getString(R.string.label_ok), null);
    	AlertDialog alert = builder.create();
    	alert.show();
		return alert;
    }
  
    /**
	 * Show an info with a specific title
	 * 
	 * @param activity Activity
	 * @param title Title of the dialog
	 * @param items List of items
	 */
    public static void showList(Activity activity, String title, Set<String> items) {
        if (activity.isFinishing()) {
        	return;
        }
        
        CharSequence[] chars = items.toArray(new CharSequence[items.size()]);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    	builder.setTitle(title);
    	builder.setCancelable(false);
    	builder.setPositiveButton(activity.getString(R.string.label_ok), null);
        builder.setItems(chars, null);
        AlertDialog alert = builder.create();
    	alert.show();
    }
    
	/**
	 * Show a progress dialog with the given parameters 
	 * 
	 * @param activity Activity
	 * @param msg Message to be displayed
	 * @return Dialog
	 */
	public static ProgressDialog showProgressDialog(Activity activity, String msg) {
        ProgressDialog dlg = new ProgressDialog(activity);
		dlg.setMessage(msg);
		dlg.setIndeterminate(true);
		dlg.setCancelable(true);
		dlg.setCanceledOnTouchOutside(false);
		dlg.show();
		return dlg;
	}    

	/**
     * Format a date to string
     * 
     * @param d Date
     * @return String
     */
    public static String formatDateToString(long d) {
    	if (d > 0L) {
	    	Date df = new Date(d);
	    	return DateFormat.getDateInstance().format(df);
    	} else {
    		return "";
    	}
    }
    
	/**
	 * Construct an NTP time from a date in milliseconds
	 *
	 * @param date Date in milliseconds
	 * @return NTP time in string format
	 */
	public static String constructNTPtime(long date) {
		long ntpTime = 2208988800L;
		long startTime = (date / 1000) + ntpTime;
		return String.valueOf(startTime);
	}

	/**
	 * Returns the local IP address
	 *
	 * @return IP address
	 */
	public static String getLocalIpAddress() {
		try {
	        for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
	            NetworkInterface intf = (NetworkInterface)en.nextElement();
	            for (Enumeration<InetAddress> addr = intf.getInetAddresses(); addr.hasMoreElements();) {
	                InetAddress inetAddress = (InetAddress)addr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress()) {
                        return inetAddress.getHostAddress().toString();
                    }
	            }
	        }
	        return null;
		} catch(Exception e) {
			return null;
		}
	}

}