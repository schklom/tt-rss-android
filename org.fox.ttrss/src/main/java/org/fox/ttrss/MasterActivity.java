package org.fox.ttrss;


import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.gson.JsonElement;

import org.fox.ttrss.types.Article;
import org.fox.ttrss.types.ArticleList;
import org.fox.ttrss.types.Feed;
import org.fox.ttrss.types.FeedCategory;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;

import icepick.State;

public class MasterActivity extends OnlineActivity implements HeadlinesEventListener {
	private final String TAG = this.getClass().getSimpleName();
	
	private static final int HEADLINES_REQUEST = 1;
	
	protected SharedPreferences m_prefs;
	protected long m_lastRefresh = 0;
	protected long m_lastWidgetRefresh = 0;
	
	@State protected boolean m_feedIsSelected = false;
    @State protected boolean m_userFeedSelected = false;

    private ActionBarDrawerToggle m_drawerToggle;
    private DrawerLayout m_drawerLayout;

	@SuppressLint("NewApi")
	@Override
	public void onCreate(Bundle savedInstanceState) {
		m_prefs = PreferenceManager
				.getDefaultSharedPreferences(getApplicationContext());

		setAppTheme(m_prefs);

		super.onCreate(savedInstanceState);

		if (m_prefs.getBoolean("force_phone_layout", false)) {
			setContentView(R.layout.activity_master_phone);
		} else {
			setContentView(R.layout.activity_master);
		}

		setSmallScreen(findViewById(R.id.sw600dp_anchor) == null);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		Application.getInstance().load(savedInstanceState);

		m_lastWidgetRefresh = new Date().getTime();

        m_drawerLayout = findViewById(R.id.headlines_drawer);

        if (m_drawerLayout != null) {

			m_drawerToggle = new ActionBarDrawerToggle(this, m_drawerLayout, R.string.blank, R.string.blank) {
                @Override
                public void onDrawerOpened(View drawerView) {
                    super.onDrawerOpened(drawerView);

                    getSupportActionBar().show();
                    invalidateOptionsMenu();

					Date date = new Date();
					if (date.getTime() - m_lastRefresh > 60*1000) {
						m_lastRefresh = date.getTime();
						refresh(false);
					}
                }

                @Override
                public void onDrawerClosed(View drawerView) {
                    super.onDrawerClosed(drawerView);

                    if (m_prefs.getBoolean("drawer_open_on_start", true)) {
                        SharedPreferences.Editor editor = m_prefs.edit();
                        editor.putBoolean("drawer_open_on_start", false);
                        editor.apply();
                    }

                    invalidateOptionsMenu();
                }
            };

            m_drawerLayout.addDrawerListener(m_drawerToggle);
            m_drawerToggle.setDrawerIndicatorEnabled(true);

            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
        }

        if (savedInstanceState == null) {
            if (m_drawerLayout != null && m_prefs.getBoolean("drawer_open_on_start", true)) {
                m_drawerLayout.openDrawer(GravityCompat.START);
            }

			final Intent i = getIntent();
			boolean shortcutMode = i.getBooleanExtra("shortcut_mode", false);
			
			Log.d(TAG, "is_shortcut_mode: " + shortcutMode);

			if (shortcutMode) {
				LoginRequest lr = new LoginRequest(this, false, new OnLoginFinishedListener() {
					
					@Override
					public void OnLoginSuccess() {
						int feedId = i.getIntExtra("feed_id", 0);
						boolean isCat = i.getBooleanExtra("feed_is_cat", false);
						String feedTitle = i.getStringExtra("feed_title");

						// app shortcuts are not allowed to pass string extras
						if (feedTitle == null)
							feedTitle = Feed.getSpecialFeedTitleById(MasterActivity.this, feedId);

						Feed tmpFeed = new Feed(feedId, feedTitle, isCat);
						
						onFeedSelected(tmpFeed, false);
					}
					
					@Override
					public void OnLoginFailed() {
						login();						
					}
				});
				
				HashMap<String, String> map = new HashMap<>();
				map.put("op", "login");
				map.put("user", m_prefs.getString("login", "").trim());
				map.put("password", m_prefs.getString("password", "").trim());

				lr.execute(map);
			}
			
			//m_pullToRefreshAttacher.setRefreshing(true);

			FragmentTransaction ft = getSupportFragmentManager().beginTransaction();

			if (m_prefs.getBoolean("enable_cats", false)) {
				ft.replace(R.id.feeds_fragment, new FeedCategoriesFragment(), FRAG_CATS);
			} else {
				ft.replace(R.id.feeds_fragment, new FeedsFragment(), FRAG_FEEDS);
			}

			// allow overriding feed to open on startup in non-shortcut mode, default to
			// open_on_startup prefs setting and not-category

			int openFeedId = i.getIntExtra("feed_id",
				Integer.parseInt(m_prefs.getString("open_on_startup", "0")));
			boolean openFeedIsCat = i.getBooleanExtra("feed_is_cat", false);

			String openFeedTitle = i.getStringExtra("feed_title");

			if (openFeedTitle == null)
				openFeedTitle = Feed.getSpecialFeedTitleById(this, openFeedId);

			if (!shortcutMode && openFeedId != 0) {
				Log.d(TAG, "opening feed id: " + openFeedId);

                HeadlinesFragment hf = new HeadlinesFragment();
                hf.initialize(new Feed(openFeedId, openFeedTitle, openFeedIsCat));
                ft.replace(R.id.headlines_fragment, hf, FRAG_HEADLINES);
            } else if (m_drawerLayout != null) {
                m_drawerLayout.openDrawer(GravityCompat.START);
            }

			ft.commit();

            m_feedIsSelected = true;

		} else { // savedInstanceState != null

			if (m_drawerLayout != null && !m_feedIsSelected) {
				m_drawerLayout.openDrawer(GravityCompat.START);
			}
		}

		FloatingActionButton fab = findViewById(R.id.master_fab);

        if (fab != null) {
        	fab.show();

        	fab.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View view) {
					HeadlinesFragment hf = (HeadlinesFragment) getSupportFragmentManager().findFragmentByTag(FRAG_HEADLINES);

					if (hf != null && hf.isAdded()) {
						hf.refresh(false);
					}
				}
			});
		}
	}

    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        // Sync the toggle state after onRestoreInstanceState has occurred.
        if (m_drawerToggle != null) m_drawerToggle.syncState();
    }

	@Override
	protected void initMenu() {
		super.initMenu();
		
		if (m_menu != null && getSessionId() != null) {
			Fragment ff = getSupportFragmentManager().findFragmentByTag(FRAG_FEEDS);
			Fragment cf = getSupportFragmentManager().findFragmentByTag(FRAG_CATS);
			HeadlinesFragment hf = (HeadlinesFragment)getSupportFragmentManager().findFragmentByTag(FRAG_HEADLINES);

			m_menu.setGroupVisible(R.id.menu_group_feeds, (ff != null && ff.isAdded()) || (cf != null && cf.isAdded()));
			m_menu.setGroupVisible(R.id.menu_group_headlines, hf != null && hf.isAdded());
		}
	}

    public void onFeedSelected(Feed feed) {
        onFeedSelected(feed, true);
    }

	public void onFeedSelected(final Feed feed, final boolean selectedByUser) {

		FeedsFragment ff = (FeedsFragment) getSupportFragmentManager().findFragmentByTag(FRAG_FEEDS);

		if (ff != null && ff.isAdded()) {
			ff.setSelectedfeed(feed);
		}

		if (m_drawerLayout != null) {
			m_drawerLayout.closeDrawers();
		}

		new Handler().postDelayed(new Runnable() {
			@Override
			public void run() {
				FragmentTransaction ft = getSupportFragmentManager()
						.beginTransaction();

				HeadlinesFragment hf = new HeadlinesFragment();
				hf.initialize(feed);

				ft.replace(R.id.headlines_fragment, hf, FRAG_HEADLINES);

				ft.commit();

				m_feedIsSelected = true;
				m_userFeedSelected = selectedByUser;

			}
		}, 250);

        Date date = new Date();

        if (date.getTime() - m_lastRefresh > 30*1000) {
            m_lastRefresh = date.getTime();
            refresh(false);
        }
	}
	
	public void onCatSelected(FeedCategory cat, boolean openAsFeed) {
		FeedCategoriesFragment fc = (FeedCategoriesFragment) getSupportFragmentManager().findFragmentByTag(FRAG_CATS);
		
		//m_pullToRefreshAttacher.setRefreshing(true);
		
		if (!openAsFeed) {
			
			if (fc != null && fc.isAdded()) {
				fc.setSelectedCategory(null);
			}

			FragmentTransaction ft = getSupportFragmentManager()
					.beginTransaction();

			FeedsFragment ff = new FeedsFragment();
			ff.initialize(cat, true);
			ft.replace(R.id.feeds_fragment, ff, FRAG_FEEDS);

			ft.addToBackStack(null);
			ft.commit();

		} else {
			
			if (fc != null) {
				fc.setSelectedCategory(cat);
			}

			Feed feed = new Feed(cat.id, cat.title, true);
			onFeedSelected(feed);
		}
	}
	
	public void onCatSelected(FeedCategory cat) {
		onCatSelected(cat, m_prefs.getBoolean("browse_cats_like_feeds", false));		
	}

    @Override
    public void logout() {
        super.logout();

        finish();
    }

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
        if (m_drawerToggle != null && m_drawerToggle.onOptionsItemSelected(item)) {
            return true;
        }

        switch (item.getItemId()) {
        case R.id.headlines_toggle_sort_order:
            LinkedHashMap<String, String> sortModes = getSortModes();

			CharSequence[] sortTitles = sortModes.values().toArray(new CharSequence[0]);
			final CharSequence[] sortNames = sortModes.keySet().toArray(new CharSequence[0]);

			String currentMode = getSortMode();

			int i = 0;
			int selectedIndex = 0;

			for (CharSequence tmp : sortNames) {
				if (tmp.equals(currentMode)) {
					selectedIndex = i;
					break;
				}

				++i;
			}

            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.headlines_sort_articles_title))
                    .setSingleChoiceItems(
							sortTitles,
                            selectedIndex, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog,
                                                    int which) {

                                	try {
//										Log.d(TAG, "sort selected index:" + which + ": " + sortNames[which]);

										setSortMode((String)sortNames[which]);

									} catch (IndexOutOfBoundsException e) {
                                		e.printStackTrace();
									}

                                    dialog.cancel();

                                    refresh();
                                }
                            });

            Dialog dialog = builder.create();
            dialog.show();

            return true;
		default:
			Log.d(TAG, "onOptionsItemSelected, unhandled id=" + item.getItemId());
			return super.onOptionsItemSelected(item);
		}
	}

    @Override
    public void onBackPressed() {
        if (m_drawerLayout != null && !m_drawerLayout.isDrawerOpen(GravityCompat.START) &&
                (getSupportFragmentManager().getBackStackEntryCount() > 0 || m_userFeedSelected)) {

            m_drawerLayout.openDrawer(GravityCompat.START);
        } else {
			try {
				super.onBackPressed();
			} catch (IllegalStateException e) {
				// java.lang.IllegalStateException: Can not perform this action after onSaveInstanceState
				e.printStackTrace();
			}
        }
    }

	@Override
	protected void loginSuccess(boolean refresh) {
		invalidateOptionsMenu();

		if (refresh) refresh();
	}
	
	@Override
	public void onSaveInstanceState(Bundle out) {
		super.onSaveInstanceState(out);	

		Application.getInstance().save(out);
	}
	
	@Override
	public void onResume() {
		super.onResume();
		invalidateOptionsMenu();

	}

	@Override
	public void onArticleListSelectionChange(ArticleList m_selectedArticles) {
		invalidateOptionsMenu();
	}

	public void onArticleSelected(Article article, boolean open) {
		if (open) {
			boolean alwaysOpenUri = m_prefs.getBoolean("always_open_uri", false);
			if (alwaysOpenUri) {
				if (article.unread) {
					article.unread = false;
					saveArticleUnread(article);
				}

				HeadlinesFragment hf = (HeadlinesFragment) getSupportFragmentManager().findFragmentByTag(FRAG_HEADLINES);
				if (hf != null) {
					hf.setActiveArticle(article);
				}

				openUri(Uri.parse(article.link));
			}
			else {
				HeadlinesFragment hf = (HeadlinesFragment) getSupportFragmentManager().findFragmentByTag(FRAG_HEADLINES);

				Intent intent = new Intent(MasterActivity.this, DetailActivity.class);
				intent.putExtra("feed", hf.getFeed());
				intent.putExtra("searchQuery", hf.getSearchQuery());
				Application.getInstance().tmpArticleList = hf.getAllArticles();
				Application.getInstance().tmpArticle = article;

				startActivityForResult(intent, HEADLINES_REQUEST);
				overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
			}
		} else {
			invalidateOptionsMenu();

            if (article.unread) {
			    article.unread = false;
			    saveArticleUnread(article);
		    }
        }
	}

    @Override
    public void onPause() {
        super.onPause();

		Date date = new Date();

		if (isFinishing() || date.getTime() - m_lastWidgetRefresh > 60*1000) {
			m_lastWidgetRefresh = date.getTime();

			CommonActivity.requestWidgetUpdate(MasterActivity.this);
		}

    }

    @Override
	public void onArticleSelected(Article article) {
		onArticleSelected(article, true);		
	}

	@Override
	public void onHeadlinesLoaded(boolean appended) {
		// TODO Auto-generated method stub
		
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == HEADLINES_REQUEST && data != null) {
            ArticleList articles = Application.getInstance().tmpArticleList;

            if (articles != null) {
                HeadlinesFragment hf = (HeadlinesFragment)getSupportFragmentManager().findFragmentByTag(FRAG_HEADLINES);

                if (hf != null) {
                    hf.setArticles(articles);
                }
            }
		}

		super.onActivityResult(requestCode, resultCode, data);
	}

	// TODO: remove; not supported on oreo
	public void createFeedShortcut(Feed feed) {
		final Intent shortcutIntent = new Intent(this, MasterActivity.class);
		shortcutIntent.putExtra("feed_id", feed.id);
		shortcutIntent.putExtra("feed_is_cat", feed.is_cat);
		shortcutIntent.putExtra("feed_title", feed.title);
		shortcutIntent.putExtra("shortcut_mode", true);
		
		Intent intent = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
		
		intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, feed.title);
		intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
		intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, Intent.ShortcutIconResource.fromContext(this, R.drawable.ic_launcher));
		intent.putExtra("duplicate", false);
		
		sendBroadcast(intent);
		
		toast(R.string.shortcut_has_been_placed_on_the_home_screen);
	}

	// TODO: remove; not supported on oreo
	public void createCategoryShortcut(FeedCategory cat) {
		createFeedShortcut(new Feed(cat.id, cat.title, true));
	}

	public void unsubscribeFeed(final Feed feed) {
		ApiRequest req = new ApiRequest(getApplicationContext()) {
			protected void onPostExecute(JsonElement result) {
				refresh();
			}
		};

		HashMap<String, String> map = new HashMap<>();
		map.put("sid", getSessionId());
		map.put("op", "unsubscribeFeed");
		map.put("feed_id", String.valueOf(feed.id));
		
		req.execute(map);

	}

}
