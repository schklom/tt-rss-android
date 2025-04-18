package org.fox.ttrss;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.DialogInterface.OnMultiChoiceClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.view.ActionMode;
import androidx.appcompat.widget.Toolbar;

import com.github.javiersantos.appupdater.AppUpdater;
import com.github.javiersantos.appupdater.enums.UpdateFrom;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.fox.ttrss.offline.OfflineActivity;
import org.fox.ttrss.offline.OfflineDownloadService;
import org.fox.ttrss.offline.OfflineUploadService;
import org.fox.ttrss.share.SubscribeActivity;
import org.fox.ttrss.types.Article;
import org.fox.ttrss.types.ArticleList;
import org.fox.ttrss.types.Feed;
import org.fox.ttrss.types.Label;
import org.fox.ttrss.util.ImageCacheService;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OnlineActivity extends CommonActivity {
	private final String TAG = this.getClass().getSimpleName();

	protected SharedPreferences m_prefs;
	protected Menu m_menu;

    protected boolean m_forceDisableActionMode = false;
	
	private ActionMode m_headlinesActionMode;
	private HeadlinesActionModeCallback m_headlinesActionModeCallback;

	private String m_lastImageHitTestUrl;
	private ConnectivityManager m_cmgr;

	public void catchupDialog(final Feed feed) {

		if (getApiLevel() >= 15) {

			int selectedIndex = 0;

			final String searchQuery;

			if (getApiLevel() >= 22) {
				HeadlinesFragment hf = (HeadlinesFragment) getSupportFragmentManager().findFragmentByTag(FRAG_HEADLINES);

				if (hf != null) {
					searchQuery = hf.getSearchQuery();
				} else {
					searchQuery = "";
				}
			} else {
				searchQuery = "";
			}

			int titleStringId = searchQuery.length() > 0 ? R.string.catchup_dialog_title_search : R.string.catchup_dialog_title;

			AlertDialog.Builder builder = new AlertDialog.Builder(this)
					.setTitle(getString(titleStringId, feed.title))
					.setSingleChoiceItems(
							new String[] {
									getString(R.string.catchup_dialog_all_articles),
									getString(R.string.catchup_dialog_1day),
									getString(R.string.catchup_dialog_1week),
									getString(R.string.catchup_dialog_2week)
							},
							selectedIndex, new OnClickListener() {
								@Override
								public void onClick(DialogInterface dialog,
													int which) {
								}
							})
					.setPositiveButton(R.string.catchup,
							new OnClickListener() {
								public void onClick(DialogInterface dialog,
													int which) {

									ListView list = ((AlertDialog)dialog).getListView();

									if (list.getCheckedItemCount() > 0) {
										int position = list.getCheckedItemPosition();

										String[] catchupModes = { "all", "1day", "1week", "2week" };
										String mode = catchupModes[position];

										catchupFeed(feed, mode, true, searchQuery);
									}
								}
							})
					.setNegativeButton(R.string.dialog_cancel,
							new Dialog.OnClickListener() {
								public void onClick(DialogInterface dialog,
													int which) {

								}
							});

			Dialog dialog = builder.create();
			dialog.show();

		} else {
			AlertDialog.Builder builder = new AlertDialog.Builder(
					this)
					.setMessage(getString(R.string.catchup_dialog_title, feed.title))
					.setPositiveButton(R.string.catchup,
							new Dialog.OnClickListener() {
								public void onClick(DialogInterface dialog,
													int which) {

									catchupFeed(feed, "all", true, "");

								}
							})
					.setNegativeButton(R.string.dialog_cancel,
							new Dialog.OnClickListener() {
								public void onClick(DialogInterface dialog,
													int which) {

								}
							});

			AlertDialog dialog = builder.create();
			dialog.show();
		}
	}

	//protected PullToRefreshAttacher m_pullToRefreshAttacher;

	protected static abstract class OnLoginFinishedListener {
		public abstract void OnLoginSuccess();
		public abstract void OnLoginFailed();
	}

    private BroadcastReceiver m_broadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context content, Intent intent) {
			if (intent.getAction().equals(OfflineUploadService.INTENT_ACTION_SUCCESS)) {
				toast(R.string.offline_sync_success);
			}
		}
	};
	
	
	@TargetApi(11)
	private class HeadlinesActionModeCallback implements ActionMode.Callback {
		
		@Override
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			return false;
		}
		
		@Override
		public void onDestroyActionMode(ActionMode mode) {
			m_headlinesActionMode = null;

            if (!m_forceDisableActionMode) {
                HeadlinesFragment hf = (HeadlinesFragment) getSupportFragmentManager().findFragmentByTag(FRAG_HEADLINES);

                if (hf != null) {
                    hf.setSelection(HeadlinesFragment.ArticlesSelection.NONE);
                }
            }

            invalidateOptionsMenu();
		}
		
		@Override
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {

			MenuInflater inflater = getMenuInflater();
			inflater.inflate(R.menu.action_mode_headlines, menu);
			
			return true;
		}
		
		@Override
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			onOptionsItemSelected(item);
			return false;
		}
	}

    protected String getSessionId() {
		return Application.getInstance().m_sessionId;
	}

	protected void setSessionId(String sessionId) {
		Application.getInstance().m_sessionId = sessionId;
	}
	
	@Override
	public void onCreate(Bundle savedInstanceState) {

		// we use that before parent onCreate so let's init locally
		m_prefs = PreferenceManager
				.getDefaultSharedPreferences(getApplicationContext());

		setAppTheme(m_prefs);

		super.onCreate(savedInstanceState);

		SharedPreferences localPrefs = getSharedPreferences("localprefs", Context.MODE_PRIVATE);
		boolean isOffline = localPrefs.getBoolean("offline_mode_active", false);

		Log.d(TAG, "m_isOffline=" + isOffline);

		setContentView(R.layout.activity_login);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		m_cmgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

		Intent intent = getIntent();

        Log.d(TAG, "intent action=" + intent.getAction());

		if (OfflineDownloadService.INTENT_ACTION_CANCEL.equals(intent.getAction())) {

			Intent serviceIntent = new Intent(
					OnlineActivity.this,
					OfflineDownloadService.class);

			stopService(serviceIntent);

			serviceIntent = new Intent();
			serviceIntent.setAction(ImageCacheService.INTENT_ACTION_ICS_STOP);
			serviceIntent.addCategory(Intent.CATEGORY_DEFAULT);
			sendBroadcast(serviceIntent);

		} else if (OfflineDownloadService.INTENT_ACTION_SWITCH_OFFLINE.equals(intent.getAction())) {
			isOffline = true;
		}

		if (isOffline) {
			switchOfflineSuccess();			
		} else {
			checkUpdates();

			m_headlinesActionModeCallback = new HeadlinesActionModeCallback();
		}
	}

	protected void checkUpdates() {
		if (m_prefs.getBoolean("check_for_updates", true) && (BuildConfig.DEBUG || BuildConfig.ENABLE_UPDATER)) {
			new AppUpdater(this)
					.setUpdateFrom(UpdateFrom.JSON)
					.setUpdateJSON(String.format("https://srv.tt-rss.org/fdroid/updates/%1$s.json", this.getPackageName()))
					.start();
		}
	}

	protected void switchOffline() {
		AlertDialog.Builder builder = new AlertDialog.Builder(this)
				.setMessage(R.string.dialog_offline_switch_prompt)
				.setPositiveButton(R.string.dialog_offline_go,
						new Dialog.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int which) {

								if (getSessionId() != null) {
									Log.d(TAG, "offline: starting");

									Intent intent = new Intent(
											OnlineActivity.this,
											OfflineDownloadService.class);
									intent.putExtra("sessionId", getSessionId());

									startService(intent);
								}
							}
						})
				.setNegativeButton(R.string.dialog_cancel,
						new Dialog.OnClickListener() {
							public void onClick(DialogInterface dialog,
									int which) {
								//
							}
						});

		AlertDialog dlg = builder.create();
		dlg.show();
	}
	

	@Override
	public void onPause() {
		super.onPause();

		try {
			unregisterReceiver(m_broadcastReceiver);
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
	}

	private void syncOfflineData() {
		Log.d(TAG, "offlineSync: starting");
		
		Intent intent = new Intent(
				OnlineActivity.this,
				OfflineUploadService.class);
		
		intent.putExtra("sessionId", getSessionId());

		startService(intent);
	}

	private void switchOfflineSuccess() {
		logout();
		// setLoadingStatus(R.string.blank, false);

		SharedPreferences localPrefs = getSharedPreferences("localprefs", Context.MODE_PRIVATE);
		SharedPreferences.Editor editor = localPrefs.edit();
		editor.putBoolean("offline_mode_active", true);
		editor.apply();

		Intent offline = new Intent(OnlineActivity.this, OfflineActivity.class);
		offline.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_CLEAR_TASK |
				Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
		offline.putExtra("initial", true);

		startActivity(offline);

		finish();
	}
	
	public void login() {
		login(false, null);
	}

	public void login(boolean refresh) {
		login(refresh, null);
	}

	public void login(boolean refresh, OnLoginFinishedListener listener) {

		if (m_prefs.getString("ttrss_url", "").trim().length() == 0) {

			setLoadingStatus(R.string.login_need_configure);

			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setMessage(R.string.dialog_need_configure_prompt)
			       .setCancelable(false)
			       .setPositiveButton(R.string.dialog_open_preferences, new DialogInterface.OnClickListener() {
			           public void onClick(DialogInterface dialog, int id) {
			   			// launch preferences
			   			
			        	   Intent intent = new Intent(OnlineActivity.this,
			        			   PreferencesActivity.class);
			        	   startActivityForResult(intent, 0);
			           }
			       })
			       .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
			           public void onClick(DialogInterface dialog, int id) {
			                dialog.cancel();
			           }
			       });
			AlertDialog alert = builder.create();
			alert.show();
			
		} else {
			setLoadingStatus(R.string.login_in_progress);
			
			LoginRequest ar = new LoginRequest(getApplicationContext(), refresh, listener);

			HashMap<String, String> map = new HashMap<String, String>();
			map.put("op", "login");
			map.put("user", m_prefs.getString("login", "").trim());
			map.put("password", m_prefs.getString("password", "").trim());

			ar.execute(map);

			setLoadingStatus(R.string.login_in_progress);
		}
	}
	
	protected void loginSuccess(boolean refresh) {
		setLoadingStatus(R.string.blank);
		
		initMenu();
	
		Intent intent = new Intent(OnlineActivity.this, MasterActivity.class);
		intent.setFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
 	   
		startActivityForResult(intent, 0);
		overridePendingTransition(0, 0);

		if (getDatabaseHelper().hasPendingOfflineData())
			syncOfflineData();
		
		finish();
	}

	@Override
	public boolean onContextItemSelected(android.view.MenuItem item) {
		/* AdapterContextMenuInfo info = (AdapterContextMenuInfo) item
				.getMenuInfo(); */
		
		final ArticlePager ap = (ArticlePager)getSupportFragmentManager().findFragmentByTag(FRAG_ARTICLE);
		
		switch (item.getItemId()) {
		case R.id.article_img_open:
			if (getLastContentImageHitTestUrl() != null) {
				try {
					openUri(Uri.parse(getLastContentImageHitTestUrl()));
				} catch (Exception e) {
					e.printStackTrace();
					toast(R.string.error_other_error);
				}
			}			
			return true;
		case R.id.article_img_copy:
			if (getLastContentImageHitTestUrl() != null) {
				copyToClipboard(getLastContentImageHitTestUrl());
			}			
			return true;
		case R.id.article_img_share:
			if (getLastContentImageHitTestUrl() != null) {
				shareImageFromUri(getLastContentImageHitTestUrl());
			}
			return true;
		case R.id.article_img_share_url:
			if (getLastContentImageHitTestUrl() != null) {
				shareText(getLastContentImageHitTestUrl());
			}
			return true;
		case R.id.article_img_view_caption:
			if (getLastContentImageHitTestUrl() != null) {
				displayImageCaption(getLastContentImageHitTestUrl(), ap.getSelectedArticle().content);
            }
            return true;
		case R.id.article_link_share:
			if (ap != null && ap.getSelectedArticle() != null) {
				shareArticle(ap.getSelectedArticle());
			}
			return true;
		case R.id.article_link_copy:
			if (ap != null && ap.getSelectedArticle() != null) {
				copyToClipboard(ap.getSelectedArticle().link);
			}
			return true;
		default:
			Log.d(TAG, "onContextItemSelected, unhandled id=" + item.getItemId());
			return super.onContextItemSelected(item);
		}
	}

	public void displayAttachments(Article article) {
		if (article != null && article.attachments != null && article.attachments.size() > 0) {
			CharSequence[] items = new CharSequence[article.attachments.size()];
			final CharSequence[] itemUrls = new CharSequence[article.attachments.size()];

			for (int i = 0; i < article.attachments.size(); i++) {
				items[i] = article.attachments.get(i).title != null ? article.attachments.get(i).content_url :
						article.attachments.get(i).content_url;

				itemUrls[i] = article.attachments.get(i).content_url;
			}

			Dialog dialog = new Dialog(OnlineActivity.this);
			AlertDialog.Builder builder = new AlertDialog.Builder(OnlineActivity.this)
					.setTitle(R.string.attachments_prompt)
					.setCancelable(true)
					.setSingleChoiceItems(items, 0, new OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							//
						}
					}).setNeutralButton(R.string.attachment_copy, new OnClickListener() {
						@Override
						public void onClick(DialogInterface dialog, int which) {
							int selectedPosition = ((AlertDialog)dialog).getListView().getCheckedItemPosition();

							copyToClipboard((String)itemUrls[selectedPosition]);
						}
					}).setPositiveButton(R.string.attachment_view, new OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int id) {
							int selectedPosition = ((AlertDialog)dialog).getListView().getCheckedItemPosition();

							openUri(Uri.parse((String)itemUrls[selectedPosition]));

							dialog.cancel();
						}
					}).setNegativeButton(R.string.dialog_cancel, new OnClickListener() {

						@Override
						public void onClick(DialogInterface dialog, int id) {
							dialog.cancel();
						}
					});

			dialog = builder.create();
			dialog.show();
		}
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		final HeadlinesFragment hf = (HeadlinesFragment) getSupportFragmentManager().findFragmentByTag(FRAG_HEADLINES);
		final ArticlePager ap = (ArticlePager)getSupportFragmentManager().findFragmentByTag(FRAG_ARTICLE);

		switch (item.getItemId()) {
		case R.id.subscribe_to_feed:
			Intent subscribe = new Intent(OnlineActivity.this, SubscribeActivity.class);
			startActivityForResult(subscribe, 0);
			return true;
		/*case R.id.toggle_attachments:
			if (true) {
				Article article = ap.getSelectedArticle();

				if (article != null) {
					displayAttachments(article);
				}
			}
			return true; */
		/*case R.id.logout:
			logout();
			return true;*/
		case R.id.login:
			login();
			return true;
		/*case R.id.go_offline:
			switchOffline();
			return true;*/
		case R.id.article_set_note:
			if (ap != null && ap.getSelectedArticle() != null) {
				editArticleNote(ap.getSelectedArticle());				
			}
			return true;
		case R.id.preferences:
			Intent intent = new Intent(OnlineActivity.this,
					PreferencesActivity.class);
			startActivityForResult(intent, 0);
			return true;
		case R.id.search:			
			if (hf != null) {
				Dialog dialog = new Dialog(this);

				final EditText edit = new EditText(this);

				AlertDialog.Builder builder = new AlertDialog.Builder(this)
						.setTitle(R.string.search)
						.setPositiveButton(getString(R.string.search),
								new OnClickListener() {

									@Override
									public void onClick(DialogInterface dialog,
											int which) {
										
										String query = edit.getText().toString().trim();
										
										hf.setSearchQuery(query);

									}
								})
						.setNegativeButton(getString(R.string.cancel),
								new OnClickListener() {

									@Override
									public void onClick(DialogInterface dialog,
											int which) {
										
										//

									}
								}).setView(edit);
				
				dialog = builder.create();
				dialog.show();
			}
			return true;
		case R.id.headlines_mark_as_read:
			if (hf != null) {

				Feed feed = hf.getFeed();

				if (feed != null) {
					catchupDialog(hf.getFeed());
				}
			}
			return true;
		case R.id.headlines_display_mode:
			if (hf != null) {
				Dialog dialog = new Dialog(this);

				String headlineMode = m_prefs.getString("headline_mode", "HL_DEFAULT");
				String[] headlineModeNames = getResources().getStringArray(R.array.headline_mode_names);
				final String[] headlineModeValues = getResources().getStringArray(R.array.headline_mode_values);

				int selectedIndex = Arrays.asList(headlineModeValues).indexOf(headlineMode);

				AlertDialog.Builder builder = new AlertDialog.Builder(this)
						.setTitle(R.string.headlines_set_view_mode)
						.setSingleChoiceItems(headlineModeNames,
								selectedIndex, new DialogInterface.OnClickListener() {
									@Override
									public void onClick(DialogInterface dialog,
														int which) {
										dialog.cancel();

										SharedPreferences.Editor editor = m_prefs.edit();
										editor.putString("headline_mode", headlineModeValues[which]);
										editor.apply();

										Intent intent = getIntent();

										Feed feed = hf.getFeed();

										if (feed != null) {
											intent.putExtra("feed_id", feed.id);
											intent.putExtra("feed_is_cat", feed.is_cat);
											intent.putExtra("feed_title", feed.title);
										}

										finish();

										startActivity(intent);
										overridePendingTransition(0, 0);
									}
								});

				dialog = builder.create();
				dialog.show();

			}
			return true;
		case R.id.headlines_view_mode:
			if (hf != null) {
				Dialog dialog = new Dialog(this);
				
				String viewMode = getViewMode();
				
				//Log.d(TAG, "viewMode:" + getViewMode());

				int selectedIndex = 0;
				
				if (viewMode.equals("all_articles")) {
					selectedIndex = 1;
				} else if (viewMode.equals("marked")) {
					selectedIndex = 2;
				} else if (viewMode.equals("published")) {
					selectedIndex = 3;
				} else if (viewMode.equals("unread")) {
					selectedIndex = 4;
				}
				
				AlertDialog.Builder builder = new AlertDialog.Builder(this)
						.setTitle(R.string.headlines_set_view_mode)
						.setSingleChoiceItems(
								new String[] {
										getString(R.string.headlines_adaptive),
										getString(R.string.headlines_all_articles),
										getString(R.string.headlines_starred),
										getString(R.string.headlines_published),
										getString(R.string.headlines_unread) },
								selectedIndex, new DialogInterface.OnClickListener() {
									@Override
									public void onClick(DialogInterface dialog,
											int which) {
										switch (which) {
										case 0:
											setViewMode("adaptive");
											break;
										case 1:
											setViewMode("all_articles");
											break;
										case 2:
											setViewMode("marked");
											break;
										case 3:
											setViewMode("published");
											break;
										case 4:
											setViewMode("unread");
											break;
										}
										dialog.cancel();

										refresh();
									}
								});

				dialog = builder.create();
				dialog.show();

			}
			return true;
		case R.id.headlines_select:
			if (hf != null) {
				Dialog dialog = new Dialog(this);
				AlertDialog.Builder builder = new AlertDialog.Builder(this)
						.setTitle(R.string.headlines_select_dialog)
						.setSingleChoiceItems(
								new String[] {
										getString(R.string.headlines_select_all),
										getString(R.string.headlines_select_unread),
										getString(R.string.headlines_select_none) },
								0, new DialogInterface.OnClickListener() {
									@Override
									public void onClick(DialogInterface dialog,
											int which) {
										switch (which) {
										case 0:
											hf.setSelection(HeadlinesFragment.ArticlesSelection.ALL);
											break;
										case 1:
											hf.setSelection(HeadlinesFragment.ArticlesSelection.UNREAD);
											break;
										case 2:
											hf.setSelection(HeadlinesFragment.ArticlesSelection.NONE);
											break;
										}
										dialog.cancel();
										invalidateOptionsMenu();
									}
								});

				dialog = builder.create();
				dialog.show();
			}
			return true;
		/* case R.id.share_article:
			if (ap != null) {
				shareArticle(ap.getSelectedArticle());
			}
			return true; */
		case R.id.toggle_marked:
			if (ap != null && ap.getSelectedArticle() != null) {
				Article a = ap.getSelectedArticle();
				a.marked = !a.marked;
				saveArticleMarked(a);
				if (hf != null) hf.notifyUpdated();
			}
			return true;
			case R.id.toggle_unread:
				if (ap != null && ap.getSelectedArticle() != null) {
					Article a = ap.getSelectedArticle();
					a.unread = !a.unread;
					saveArticleUnread(a);
					if (hf != null) hf.notifyUpdated();
				}
				return true;
		/* case R.id.selection_select_none:
			if (hf != null) {
				ArticleList selected = hf.getSelectedArticles();
				if (selected.size() > 0) {
					selected.clear();
					invalidateOptionsMenu();
					hf.notifyUpdated();
				}
			}
			return true; */
		case R.id.selection_toggle_unread:
			if (hf != null) {
				ArticleList selected = hf.getSelectedArticles();

				if (selected.size() > 0) {
					for (Article a : selected)
						a.unread = !a.unread;

					toggleArticlesUnread(selected);
					hf.notifyUpdated();
					invalidateOptionsMenu();
				}
			}
			return true;
		case R.id.selection_toggle_marked:
			if (hf != null) {
				ArticleList selected = hf.getSelectedArticles();

				if (selected.size() > 0) {
					for (Article a : selected)
						a.marked = !a.marked;

					toggleArticlesMarked(selected);
					hf.notifyUpdated();
					invalidateOptionsMenu();
				}
			}
			return true;
		case R.id.selection_toggle_published:
			if (hf != null) {
				ArticleList selected = hf.getSelectedArticles();

				if (selected.size() > 0) {
					for (Article a : selected)
						a.published = !a.published;

					toggleArticlesPublished(selected);
					hf.notifyUpdated();
					invalidateOptionsMenu();
				}
			}
			return true;
		case R.id.toggle_published:
			if (ap != null && ap.getSelectedArticle() != null) {
				Article a = ap.getSelectedArticle();
				a.published = !a.published;
				saveArticlePublished(a);
				if (hf != null) hf.notifyUpdated();
			}
			return true;
		case R.id.catchup_above:
			if (hf != null) {

				AlertDialog.Builder builder = new AlertDialog.Builder(
						OnlineActivity.this)
						.setMessage(R.string.confirm_catchup_above)
						.setPositiveButton(R.string.dialog_ok,
								new Dialog.OnClickListener() {
									public void onClick(DialogInterface dialog,
														int which) {

										catchupAbove(hf, ap);

									}
								})
						.setNegativeButton(R.string.dialog_cancel,
								new Dialog.OnClickListener() {
									public void onClick(DialogInterface dialog,
														int which) {

									}
								});

				AlertDialog dialog = builder.create();
				dialog.show();

			}
			return true;
		case R.id.set_labels:
			if (ap != null && ap.getSelectedArticle() != null) {
				if (getApiLevel() != 7) {
					editArticleLabels(ap.getSelectedArticle());					
				} else {
					toast(R.string.server_function_not_available);
				}				
								
			}
			return true;
		/*case R.id.update_headlines:
			if (hf != null) {
				//m_pullToRefreshAttacher.setRefreshing(true);
				hf.refresh(false, true);
			}
			return true;*/
		default:
			Log.d(TAG, "onOptionsItemSelected, unhandled id=" + item.getItemId());
			return super.onOptionsItemSelected(item);
		}
	}

	private void catchupAbove(HeadlinesFragment hf, ArticlePager ap) {
		if (ap != null && ap.getSelectedArticle() != null) {
            Article article = ap.getSelectedArticle();

            ArticleList articles = hf.getAllArticles();
            ArticleList tmp = new ArticleList();
            for (Article a : articles) {
                if (article.id == a.id)
                    break;

                if (a.unread) {
                    a.unread = false;
                    tmp.add(a);
                }
            }
            if (tmp.size() > 0) {
                toggleArticlesUnread(tmp);
                hf.notifyUpdated();
                invalidateOptionsMenu();
            }
        }
	}

	/* protected void catchupVisibleArticles() {
		final HeadlinesFragment hf = (HeadlinesFragment) getSupportFragmentManager().findFragmentByTag(FRAG_HEADLINES);
		
		if (hf != null) {
			ArticleList articles = hf.getUnreadArticles();
			
			for (Article a : articles)
				a.unread = false;
	
			ApiRequest req = new ApiRequest(getApplicationContext()) {
				protected void onPostExecute(JsonElement result) {
					if (hf.isAdded()) {
						hf.refresh(false);
					}
				}
			};
	
			final String articleIds = articlesToIdString(articles);
	
			@SuppressWarnings("serial")
			HashMap<String, String> map = new HashMap<String, String>() {
				{
					put("sid", getSessionId());
					put("op", "updateArticle");
					put("article_ids", articleIds);
					put("mode", "0");
					put("field", "2");
				}
			};
			req.execute(map);
		}
	} */

	public void editArticleNote(final Article article) {
		String note = "";
		
		AlertDialog.Builder builder = new AlertDialog.Builder(this);  
		builder.setTitle(article.title);
		final EditText topicEdit = new EditText(this);
		topicEdit.setText(note);
		builder.setView(topicEdit);
		
		builder.setPositiveButton(R.string.article_set_note, new Dialog.OnClickListener() {
	        public void onClick(DialogInterface dialog, int which) {
	        	String note = topicEdit.getText().toString().trim();
	        	
	        	saveArticleNote(article, note);
	        	article.published = true;	
	        	article.note = note;
	        	
	        	saveArticlePublished(article);
	        	
	        	HeadlinesFragment hf = (HeadlinesFragment) getSupportFragmentManager().findFragmentByTag(FRAG_HEADLINES);
	        	if (hf != null) hf.notifyUpdated();
	        }
	    });
		
		builder.setNegativeButton(R.string.dialog_cancel, new Dialog.OnClickListener() {
	        public void onClick(DialogInterface dialog, int which) {
	        	//
	        }
	    });
		
		AlertDialog dialog = builder.create();
		dialog.show();
	}
	
	public void editArticleLabels(Article article) {
		final int articleId = article.id;									

		ApiRequest req = new ApiRequest(getApplicationContext()) {
			@Override
			protected void onPostExecute(JsonElement result) {
				if (result != null) {
					Type listType = new TypeToken<List<Label>>() {}.getType();
					final List<Label> labels = new Gson().fromJson(result, listType);

					CharSequence[] items = new CharSequence[labels.size()];
					final int[] itemIds = new int[labels.size()];
					boolean[] checkedItems = new boolean[labels.size()];
					
					for (int i = 0; i < labels.size(); i++) {
						items[i] = labels.get(i).caption;
						itemIds[i] = labels.get(i).id;
						checkedItems[i] = labels.get(i).checked;
					}
					
					Dialog dialog = new Dialog(OnlineActivity.this);
					AlertDialog.Builder builder = new AlertDialog.Builder(OnlineActivity.this)
							.setTitle(R.string.article_set_labels)
							.setMultiChoiceItems(items, checkedItems, new OnMultiChoiceClickListener() {
								
								@Override
								public void onClick(DialogInterface dialog, int which, final boolean isChecked) {
									final int labelId = itemIds[which];
									
									@SuppressWarnings("serial")
									HashMap<String, String> map = new HashMap<String, String>();
									map.put("sid", getSessionId());
									map.put("op", "setArticleLabel");
									map.put("label_id", String.valueOf(labelId));
									map.put("article_ids", String.valueOf(articleId));
									if (isChecked) map.put("assign", "true");
									
									ApiRequest req = new ApiRequest(OnlineActivity.this);
									req.execute(map);
									
								}
							}).setPositiveButton(R.string.dialog_close, new OnClickListener() {
								
								@Override
								public void onClick(DialogInterface dialog, int which) {
									dialog.cancel();
								}
							});

					dialog = builder.create();
					dialog.show();

				}
			}
		};
		
		@SuppressWarnings("serial")
		HashMap<String, String> map = new HashMap<String, String>();
		map.put("sid", getSessionId());
		map.put("op", "getLabels");
		map.put("article_id", String.valueOf(articleId));
		
		req.execute(map);
	}

    private void setLoadingStatus(int status) {
		setLoadingStatus(getString(status));
    }

	private void setLoadingStatus(String status) {
		TextView tv = findViewById(R.id.loading_message);

		if (tv != null) {
			tv.setText(status);
		}
	}

    protected void logout() {
		setSessionId(null);

		setLoadingStatus(R.string.login_ready);

		initMenu();
	}

	protected void loginFailure() {
		setSessionId(null);
		initMenu();
		
		if (getDatabaseHelper().hasOfflineData()) {

			AlertDialog.Builder builder = new AlertDialog.Builder(
					OnlineActivity.this)
					.setMessage(R.string.dialog_offline_prompt)
					.setPositiveButton(R.string.dialog_offline_go,
							new Dialog.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int which) {
									switchOfflineSuccess();
								}
							})
					.setNegativeButton(R.string.dialog_cancel,
							new Dialog.OnClickListener() {
								public void onClick(DialogInterface dialog,
										int which) {
									//
								}
							});

			AlertDialog dlg = builder.create();
			dlg.show();
		}
	}

	@Override
	public void onResume() {
		super.onResume();

		IntentFilter filter = new IntentFilter();
		//filter.addAction(OfflineDownloadService.INTENT_ACTION_SUCCESS);
		filter.addAction(OfflineUploadService.INTENT_ACTION_SUCCESS);
		filter.addCategory(Intent.CATEGORY_DEFAULT);

		registerReceiver(m_broadcastReceiver, filter);
		
		if (getSessionId() == null) {
			login();
		} else {
			loginSuccess(false);
		}
	}
	
	public Menu getMenu() {
		return m_menu;
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.activity_main, menu);

		m_menu = menu;

		initMenu();

		return true;
	}

	public int getApiLevel() {
		return Application.getInstance().m_apiLevel;
	}

	protected void setApiLevel(int apiLevel) {
		Application.getInstance().m_apiLevel = apiLevel;
	}
	
	@SuppressWarnings({ "unchecked", "serial" })
	public void saveArticleUnread(final Article article) {
		ApiRequest req = new ApiRequest(getApplicationContext()) {
			protected void onPostExecute(JsonElement result) {
				//toast(R.string.article_set_unread);
				initMenu();
			}
		};

		HashMap<String, String> map = new HashMap<String, String>();
		map.put("sid", getSessionId());
		map.put("op", "updateArticle");
		map.put("article_ids", String.valueOf(article.id));
		map.put("mode", article.unread ? "1" : "0");
		map.put("field", "2");

		req.execute(map);
	}

	public void saveArticleScore(final Article article) {
		ApiRequest req = new ApiRequest(getApplicationContext()) {
			protected void onPostExecute(JsonElement result) {
				//toast(article.marked ? R.string.notify_article_marked : R.string.notify_article_unmarked);
				invalidateOptionsMenu();
			}
		};

		HashMap<String, String> map = new HashMap<String, String>();
		map.put("sid", getSessionId());
		map.put("op", "updateArticle");
		map.put("article_ids", String.valueOf(article.id));
		map.put("data", String.valueOf(article.score));
		map.put("field", "4");

		req.execute(map);
	}

	public void saveArticleMarked(final Article article) {
		ApiRequest req = new ApiRequest(getApplicationContext()) {
			protected void onPostExecute(JsonElement result) {
				//toast(article.marked ? R.string.notify_article_marked : R.string.notify_article_unmarked);
				invalidateOptionsMenu();
			}
		};

		HashMap<String, String> map = new HashMap<String, String>();
		map.put("sid", getSessionId());
		map.put("op", "updateArticle");
		map.put("article_ids", String.valueOf(article.id));
		map.put("mode", article.marked ? "1" : "0");
		map.put("field", "0");
		
		req.execute(map);
	}

	@SuppressWarnings({ "unchecked", "serial" })
	public void saveArticlePublished(final Article article) {

		ApiRequest req = new ApiRequest(getApplicationContext()) {
			protected void onPostExecute(JsonElement result) {
				//toast(article.published ? R.string.notify_article_published : R.string.notify_article_unpublished);
				invalidateOptionsMenu();
			}
		};

		HashMap<String, String> map = new HashMap<String, String>();
		map.put("sid", getSessionId());
		map.put("op", "updateArticle");
		map.put("article_ids", String.valueOf(article.id));
		map.put("mode", article.published ? "1" : "0");
		map.put("field", "1");

		req.execute(map);
	}

	@SuppressWarnings({ "unchecked", "serial" })
	public void saveArticleNote(final Article article, final String note) {
		ApiRequest req = new ApiRequest(getApplicationContext()) {
			protected void onPostExecute(JsonElement result) {
				//
			}
		};

		HashMap<String, String> map = new HashMap<String, String>();
		map.put("sid", getSessionId());
		map.put("op", "updateArticle");
		map.put("article_ids", String.valueOf(article.id));
		map.put("mode", "1");
		map.put("data", note);
		map.put("field", "3");

		req.execute(map);
	}

	public static String articlesToIdString(ArticleList articles) {
		String tmp = "";

		for (Article a : articles)
			tmp += a.id + ",";

		return tmp.replaceAll(",$", "");
	}
	
	public void shareArticle(Article article) {
		if (article != null) {
			shareText(article.link, article.title);
		}
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {		
		ArticlePager ap = (ArticlePager) getSupportFragmentManager().findFragmentByTag(FRAG_ARTICLE);
		HeadlinesFragment hf = (HeadlinesFragment) getSupportFragmentManager().findFragmentByTag(FRAG_HEADLINES);

		switch (keyCode) {
		case KeyEvent.KEYCODE_DPAD_LEFT:
			if (ap != null && ap.isAdded()) {
				ap.selectArticle(false);
				return true;
			}
			break;
		case KeyEvent.KEYCODE_DPAD_RIGHT:
			if (ap != null && ap.isAdded()) {
				ap.selectArticle(true);
				return true;
			}
			break;
		case KeyEvent.KEYCODE_ESCAPE:
			moveTaskToBack(true);
			return true;
		case KeyEvent.KEYCODE_O:
			if (ap != null && ap.getSelectedArticle() != null) {
				openUri(Uri.parse(ap.getSelectedArticle().link));
				return true;
			}
			break;
		case KeyEvent.KEYCODE_R:
			refresh();
			return true;
		case KeyEvent.KEYCODE_U:
			if (ap != null && ap.getSelectedArticle() != null) {
				Article a = ap.getSelectedArticle();
				a.unread = !a.unread;
				saveArticleUnread(a);
				if (hf != null) hf.notifyUpdated();
			}
			return true;
		}

		if (m_prefs.getBoolean("use_volume_keys", false)) {
			
			if (ap != null && ap.isAdded()) {			
				switch (keyCode) {
				case KeyEvent.KEYCODE_VOLUME_UP:
					ap.selectArticle(false);					
					return true;
				case KeyEvent.KEYCODE_VOLUME_DOWN:
					ap.selectArticle(true);
					return true;
				}
			}
		}
		
		return super.onKeyDown(keyCode, event);			
	}
	
	// Handle onKeyUp too to suppress beep
	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if (m_prefs.getBoolean("use_volume_keys", false)) {
					
			switch (keyCode) {
			case KeyEvent.KEYCODE_VOLUME_UP:
			case KeyEvent.KEYCODE_VOLUME_DOWN:
				return true;
			}
		}
		
		return super.onKeyUp(keyCode, event);		
	}

	public void catchupFeed(final Feed feed, final String mode, final boolean refreshAfter, final String searchQuery) {
		Log.d(TAG, "catchupFeed=" + feed + "; mode=" + mode + "; search=" + searchQuery);

		ApiRequest req = new ApiRequest(getApplicationContext()) {
			protected void onPostExecute(JsonElement result) {
				if (refreshAfter)
					refresh();
			}
		};

		@SuppressWarnings("serial")
		HashMap<String, String> map = new HashMap<String, String>();
		map.put("sid", getSessionId());
		map.put("op", "catchupFeed");
		map.put("feed_id", String.valueOf(feed.id));
		map.put("search_query", searchQuery);
		map.put("search_lang", ""); // for the time being always user per-user default
		map.put("mode", mode);
		if (feed.is_cat)
			map.put("is_cat", "1");

		req.execute(map);
	}
	
	public void toggleArticlesMarked(final ArticleList articles) {
		ApiRequest req = new ApiRequest(getApplicationContext());

		@SuppressWarnings("serial")
		HashMap<String, String> map = new HashMap<String, String>();
		map.put("sid", getSessionId());
		map.put("op", "updateArticle");
		map.put("article_ids", articlesToIdString(articles));
		map.put("mode", "2");
		map.put("field", "0");

		req.execute(map);
	}

	public void toggleArticlesUnread(final ArticleList articles) {
		ApiRequest req = new ApiRequest(getApplicationContext());

		@SuppressWarnings("serial")
		HashMap<String, String> map = new HashMap<String, String>();
		map.put("sid", getSessionId());
		map.put("op", "updateArticle");
		map.put("article_ids", articlesToIdString(articles));
		map.put("mode", "2");
		map.put("field", "2");

		req.execute(map);
		//refresh();
	}

	public void toggleArticlesPublished(final ArticleList articles) {
		ApiRequest req = new ApiRequest(getApplicationContext());

		@SuppressWarnings("serial")
		HashMap<String, String> map = new HashMap<String, String>();
		map.put("sid", getSessionId());
		map.put("op", "updateArticle");
		map.put("article_ids", articlesToIdString(articles));
		map.put("mode", "2");
		map.put("field", "1");

		req.execute(map);
	}
	
	// this may be called after activity has been destroyed (i.e. long asynctask)
	// might as well prevent null pointers if menu items are missing
	protected void initMenu() {
		try {
			if (m_menu != null) {
				if (getSessionId() != null) {
					m_menu.setGroupVisible(R.id.menu_group_logged_in, true);
					m_menu.setGroupVisible(R.id.menu_group_logged_out, false);
				} else {
					m_menu.setGroupVisible(R.id.menu_group_logged_in, false);
					m_menu.setGroupVisible(R.id.menu_group_logged_out, true);
				}

				m_menu.setGroupVisible(R.id.menu_group_headlines, false);
				m_menu.setGroupVisible(R.id.menu_group_article, false);
				m_menu.setGroupVisible(R.id.menu_group_feeds, false);

				m_menu.findItem(R.id.set_labels).setEnabled(getApiLevel() >= 1);
				m_menu.findItem(R.id.article_set_note).setEnabled(getApiLevel() >= 1);
				m_menu.findItem(R.id.subscribe_to_feed).setEnabled(getApiLevel() >= 5);

				MenuItem search = m_menu.findItem(R.id.search);
				search.setEnabled(getApiLevel() >= 2);

				ArticlePager ap = (ArticlePager) getSupportFragmentManager().findFragmentByTag(FRAG_ARTICLE);

				if (ap != null) {
					Article article = ap.getSelectedArticle();

					if (article != null) {
						m_menu.findItem(R.id.toggle_marked).setIcon(article.marked ? R.drawable.ic_star :
								R.drawable.ic_star_outline);

						m_menu.findItem(R.id.toggle_published).setIcon(article.published ? R.drawable.ic_checkbox_marked :
								R.drawable.ic_rss_box);

						m_menu.findItem(R.id.toggle_unread).setIcon(article.unread ? R.drawable.ic_email :
								R.drawable.ic_email_open);
					}
				}

				HeadlinesFragment hf = (HeadlinesFragment) getSupportFragmentManager().findFragmentByTag(FRAG_HEADLINES);

				if (hf != null && !m_forceDisableActionMode) {
					if (hf.getSelectedArticles().size() > 0) {
						if (m_headlinesActionMode == null) {
							m_headlinesActionMode = startSupportActionMode(m_headlinesActionModeCallback);
						}

						m_headlinesActionMode.setTitle(String.valueOf(hf.getSelectedArticles().size()));
					} else if (hf.getSelectedArticles().size() == 0 && m_headlinesActionMode != null) {
						m_headlinesActionMode.finish();
					}
				} else if (m_forceDisableActionMode && m_headlinesActionMode != null) {
					m_headlinesActionMode.finish();
				}
			}
		} catch (NullPointerException e) {
			e.printStackTrace();
		}
	}
	
	protected void refresh(boolean includeHeadlines) {
		FeedCategoriesFragment cf = (FeedCategoriesFragment) getSupportFragmentManager().findFragmentByTag(FRAG_CATS);
		
		if (cf != null) {
			cf.refresh();
		}

		FeedsFragment ff = (FeedsFragment) getSupportFragmentManager().findFragmentByTag(FRAG_FEEDS);
		
		if (ff != null) {
			ff.refresh();
		}

		if (includeHeadlines) {
			HeadlinesFragment hf = (HeadlinesFragment) getSupportFragmentManager().findFragmentByTag(FRAG_HEADLINES);
		
			if (hf != null) {
				hf.refresh(false);
			}
			
			ArticlePager af = (ArticlePager) getSupportFragmentManager().findFragmentByTag(FRAG_ARTICLE);
			
			if (af != null) {
				af.refresh(false);
			}
		}
	}
	
	protected void refresh() {
		refresh(true);
	}
	
	protected class LoginRequest extends ApiRequest {
		boolean m_refreshAfterLogin = false;
		OnLoginFinishedListener m_listener;
		
		public LoginRequest(Context context, boolean refresh, OnLoginFinishedListener listener) {
			super(context);
			m_refreshAfterLogin = refresh;
			m_listener = listener;
		}

		@SuppressWarnings("unchecked")
		@SuppressLint("StaticFieldLeak")
		protected void onPostExecute(JsonElement result) {
			if (result != null) {
				try {
					JsonObject content = result.getAsJsonObject();
					
					if (content != null) {
						setSessionId(content.get("session_id").getAsString());
						
						JsonElement apiLevel = content.get("api_level");

						Log.d(TAG, "Authenticated!");
						
						if (apiLevel != null) {
							setApiLevel(apiLevel.getAsInt());
							Log.d(TAG, "Received API level: " + getApiLevel());

							// get custom sort from configuration object
							if (getApiLevel() >= 17) {

								// daemon_is_running, icons_dir, etc...
								JsonObject config = content.get("config").getAsJsonObject();

								Type hashType = new TypeToken<Map<String, String>>(){}.getType();
								Map<String, String> customSortTypes = new Gson().fromJson(config.get("custom_sort_types"), hashType);

								setCustomSortModes(customSortTypes);

								Log.d(TAG, "test");
							}
							
							if (m_listener != null) {
								m_listener.OnLoginSuccess();
							} else {
								loginSuccess(m_refreshAfterLogin);
							}
							
						} else {

							ApiRequest req = new ApiRequest(OnlineActivity.this) {
								protected void onPostExecute(JsonElement result) {
									setApiLevel(0);
	
									if (result != null) {
										try {
											setApiLevel(result.getAsJsonObject().get("level").getAsInt());
										} catch (Exception e) {
											e.printStackTrace();
										}
									} else if (m_lastError != ApiCommon.ApiError.API_UNKNOWN_METHOD) {
										// Unknown method means old tt-rss, in that case we assume API 0 and continue
										
										setLoadingStatus(getErrorMessage());

										if (m_lastErrorMessage != null) {
											setLoadingStatus(getString(getErrorMessage()) + "\n\n" + m_lastErrorMessage);
										} else {
											setLoadingStatus(getErrorMessage());
										}
										
										if (m_listener != null) {
											m_listener.OnLoginFailed();
										} else {
											loginFailure();
										}
										
										return;
									}
	
									Log.d(TAG, "Received API level: " + getApiLevel());
	
									loginSuccess(m_refreshAfterLogin);
	
									return;
								}
							};
	
							@SuppressWarnings("serial")
							HashMap<String, String> map = new HashMap<String, String>();
							map.put("sid", getSessionId());
							map.put("op", "getApiLevel");
	
							req.execute(map);
	
							setLoadingStatus(R.string.loading_message);
						}

						return;
					}

				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			setSessionId(null);

			if (m_lastErrorMessage != null) {
				setLoadingStatus(getString(getErrorMessage()) + "\n\n" + m_lastErrorMessage);
			} else {
				setLoadingStatus(getErrorMessage());
			}
			
			loginFailure();
		}

	}

	public LinkedHashMap<String, String> getSortModes() {
		LinkedHashMap<String, String> tmp = new LinkedHashMap<String, String>();

		tmp.put("default", getString(R.string.headlines_sort_default));
		tmp.put("feed_dates", getString(R.string.headlines_sort_newest_first));
		tmp.put("date_reverse", getString(R.string.headlines_sort_oldest_first));
		tmp.put("title", getString(R.string.headlines_sort_title));

		tmp.putAll(Application.getInstance().m_customSortModes);

		return tmp;
	}

	public String getSortMode() {
        return m_prefs.getString("headlines_sort_mode", "default");
    }

    public void setSortMode(String sortMode) {
        SharedPreferences.Editor editor = m_prefs.edit();
        editor.putString("headlines_sort_mode", sortMode);
		editor.apply();
    }

	private synchronized void setCustomSortModes(Map<String, String> modes) {
		Application.getInstance().m_customSortModes.clear();
		Application.getInstance().m_customSortModes.putAll(modes);
	}

    public void setViewMode(String viewMode) {
		SharedPreferences.Editor editor = m_prefs.edit();
		editor.putString("view_mode", viewMode);
		editor.apply();
	}

	public String getViewMode() {
		return m_prefs.getString("view_mode", "adaptive");
	}
	
	public void setLastContentImageHitTestUrl(String url) {
		m_lastImageHitTestUrl = url;		
	}
	
	public String getLastContentImageHitTestUrl() {
		return m_lastImageHitTestUrl;
	}

	public boolean isWifiConnected() {
		NetworkInfo wifi = m_cmgr.getNetworkInfo(ConnectivityManager.TYPE_WIFI);

		if (wifi != null)
			return wifi.isConnected();

		return false;
	}

	public int getResizeWidth() {
		Display display = getWindowManager().getDefaultDisplay();
		Point size = new Point();
		display.getSize(size);

		return size.x > size.y ? (int)(size.y * 0.75) : (int)(size.x * 0.75);
	}
}
