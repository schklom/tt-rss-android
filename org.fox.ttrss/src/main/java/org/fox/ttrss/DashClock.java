package org.fox.ttrss;

import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.google.android.apps.dashclock.api.DashClockExtension;
import com.google.android.apps.dashclock.api.ExtensionData;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.fox.ttrss.util.SimpleLoginManager;

import java.util.HashMap;

public class DashClock extends DashClockExtension {
  
	private final String TAG = this.getClass().getSimpleName();
	
	protected SharedPreferences m_prefs;
	
	@Override
	protected void onInitialize(boolean isReconnect) {
		super.onInitialize(isReconnect);
		setUpdateWhenScreenOn(true);
		
		m_prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
	}

	@Override
	protected void onUpdateData(int reason) {
		
		SimpleLoginManager loginManager = new SimpleLoginManager() {
			
			@Override
			protected void onLoginSuccess(int requestId, String sessionId, int apiLevel) {
			
				ApiRequest aru = new ApiRequest(getApplicationContext()) {
						@Override
						protected void onPostExecute(JsonElement result) {
							if (result != null) {
								try {
									JsonObject content = result.getAsJsonObject();
									
									if (content != null) {
										int unread = content.get("unread").getAsInt();

										ExtensionData updatedData = null; // when null DashClock hides the widget
										
										if (unread > 0) {
											updatedData = new ExtensionData();
											updatedData.visible(true);
						
											updatedData.icon(R.drawable.dashclock);
											updatedData.status(String.valueOf(unread));
						
											updatedData.expandedTitle(getResources().getQuantityString(R.plurals.n_unread_articles, unread, unread));
											//updatedData.expandedBody(getString(R.string.app_name));
						
											updatedData.clickIntent(new Intent().setClassName("org.fox.ttrss",
													"org.fox.ttrss.LaunchActivity"));
										}
										
										publishUpdate(updatedData);				
										
										return;
									}
								} catch (Exception e) {
									e.printStackTrace();
								}
							}	   										
						
						}
				};
				
				final String fSessionId = sessionId;
				
				HashMap<String, String> umap = new HashMap<String, String>();
				umap.put("op", "getUnread");
				umap.put("sid", fSessionId);

					aru.execute(umap);
			}
			
			@Override
			protected void onLoginFailed(int requestId, ApiRequest ar) {

			}
			
			@Override
			protected void onLoggingIn(int requestId) {
				
				
			}
		};

		String login = m_prefs.getString("login", "").trim();
		String password = m_prefs.getString("password", "").trim();
		
		loginManager.logIn(getApplicationContext(), 1, login, password);
	}
}
