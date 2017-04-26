package jp.juggler.subwaytooter;

import android.net.Uri;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.os.AsyncTaskCompat;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.juggler.subwaytooter.api.TootApiClient;
import jp.juggler.subwaytooter.api.TootApiResult;
import jp.juggler.subwaytooter.api.entity.TootAccount;
import jp.juggler.subwaytooter.api.entity.TootAttachment;
import jp.juggler.subwaytooter.api.entity.TootContext;
import jp.juggler.subwaytooter.api.entity.TootGap;
import jp.juggler.subwaytooter.api.entity.TootNotification;
import jp.juggler.subwaytooter.api.entity.TootReport;
import jp.juggler.subwaytooter.api.entity.TootResults;
import jp.juggler.subwaytooter.api.entity.TootStatus;
import jp.juggler.subwaytooter.table.SavedAccount;
import jp.juggler.subwaytooter.util.LogCategory;
import jp.juggler.subwaytooter.util.Utils;

class Column {
	private static final LogCategory log = new LogCategory( "Column" );
	
	private static Object getParamAt( Object[] params, int idx ){
		if( params == null || idx >= params.length ){
			throw new IndexOutOfBoundsException( "getParamAt idx=" + idx );
		}
		return params[ idx ];
	}
	
	private static final int READ_LIMIT = 80; // API側の上限が80です
	private static final long LOOP_TIMEOUT = 10000L;
	
	// ステータスのリストを返すAPI
	private static final String PATH_HOME = "/api/v1/timelines/home?limit=" + READ_LIMIT;
	private static final String PATH_LOCAL = "/api/v1/timelines/public?limit=" + READ_LIMIT + "&local=1";
	private static final String PATH_FEDERATE = "/api/v1/timelines/public?limit=" + READ_LIMIT;
	private static final String PATH_FAVOURITES = "/api/v1/favourites?limit=" + READ_LIMIT;
	private static final String PATH_ACCOUNT_STATUSES = "/api/v1/accounts/%d/statuses?limit=" + READ_LIMIT; // 1:account_id
	private static final String PATH_HASHTAG = "/api/v1/timelines/tag/%s?limit=" + READ_LIMIT; // 1: hashtag(url encoded)
	
	// アカウントのリストを返すAPI
	private static final String PATH_ACCOUNT_FOLLOWING = "/api/v1/accounts/%d/following?limit=" + READ_LIMIT; // 1:account_id
	private static final String PATH_ACCOUNT_FOLLOWERS = "/api/v1/accounts/%d/followers?limit=" + READ_LIMIT; // 1:account_id
	private static final String PATH_MUTES = "/api/v1/mutes?limit=" + READ_LIMIT; // 1:account_id
	private static final String PATH_BLOCKS = "/api/v1/blocks?limit=" + READ_LIMIT; // 1:account_id
	
	// 他のリストを返すAPI
	private static final String PATH_REPORTS = "/api/v1/reports?limit=" + READ_LIMIT;
	private static final String PATH_NOTIFICATIONS = "/api/v1/notifications?limit=" + READ_LIMIT;
	
	// リストではなくオブジェクトを返すAPI
	private static final String PATH_ACCOUNT = "/api/v1/accounts/%d"; // 1:account_id
	private static final String PATH_STATUSES = "/api/v1/statuses/%d"; // 1:status_id
	private static final String PATH_STATUSES_CONTEXT = "/api/v1/statuses/%d/context"; // 1:status_id
	private static final String PATH_SEARCH = "/api/v1/search?q=%s"; // 1: query(urlencoded) , also, append "&resolve=1" if resolve non-local accounts
	
	private static final String KEY_ACCOUNT_ROW_ID = "account_id";
	private static final String KEY_TYPE = "type";
	private static final String KEY_WITH_ATTACHMENT = "with_attachment";
	private static final String KEY_PROFILE_ID = "profile_id";
	private static final String KEY_PROFILE_TAB = "tab";
	private static final String KEY_STATUS_ID = "status_id";
	private static final String KEY_HASHTAG = "hashtag";
	private static final String KEY_SEARCH_QUERY = "search_query";
	private static final String KEY_SEARCH_RESOLVE = "search_resolve";
	
	static final String KEY_COLUMN_ACCESS = "column_access";
	static final String KEY_COLUMN_NAME = "column_name";
	static final String KEY_OLD_INDEX = "old_index";
	
	static final int TYPE_HOME = 1;
	static final int TYPE_LOCAL = 2;
	static final int TYPE_FEDERATE = 3;
	static final int TYPE_PROFILE = 4;
	static final int TYPE_FAVOURITES = 5;
	static final int TYPE_REPORTS = 6;
	static final int TYPE_NOTIFICATIONS = 7;
	static final int TYPE_CONVERSATION = 8;
	static final int TYPE_HASHTAG = 9;
	static final int TYPE_SEARCH = 10;
	static final int TYPE_MUTES = 11;
	static final int TYPE_BLOCKS = 12;
	
	@NonNull private final ActMain activity;
	@NonNull final SavedAccount access_info;
	
	final int type;
	
	boolean with_attachment;
	
	private long profile_id;
	volatile TootAccount who_account;
	int profile_tab = TAB_STATUS;
	static final int TAB_STATUS = 0;
	static final int TAB_FOLLOWING = 1;
	static final int TAB_FOLLOWERS = 2;
	
	private long status_id;
	
	private String hashtag;
	
	String search_query;
	boolean search_resolve;
	
	int scroll_pos;
	int scroll_y;
	
	Column( @NonNull ActMain activity, @NonNull SavedAccount access_info, int type, Object... params ){
		this.activity = activity;
		this.access_info = access_info;
		this.type = type;
		switch( type ){
		
		case TYPE_CONVERSATION:
			this.status_id = (Long) getParamAt( params, 0 );
			break;
		
		case TYPE_PROFILE:
			this.profile_id = (Long) getParamAt( params, 0 );
			break;
		
		case TYPE_HASHTAG:
			this.hashtag = (String) getParamAt( params, 0 );
			break;
		
		case TYPE_SEARCH:
			this.search_query = (String) getParamAt( params, 0 );
			this.search_resolve = (Boolean) getParamAt( params, 1 );
			break;
			
		}
		
		startLoading();
	}
	
	void encodeJSON( JSONObject item, int old_index ) throws JSONException{
		item.put( KEY_ACCOUNT_ROW_ID, access_info.db_id );
		item.put( KEY_TYPE, type );
		item.put( KEY_WITH_ATTACHMENT, with_attachment );
		
		switch( type ){
		case TYPE_CONVERSATION:
			item.put( KEY_STATUS_ID, status_id );
			break;
		case TYPE_PROFILE:
			item.put( KEY_PROFILE_ID, profile_id );
			item.put( KEY_PROFILE_TAB, profile_tab );
			break;
		case TYPE_HASHTAG:
			item.put( KEY_HASHTAG, hashtag );
			break;
		case TYPE_SEARCH:
			item.put( KEY_SEARCH_QUERY, search_query );
			item.put( KEY_SEARCH_RESOLVE, search_resolve );
			break;
		}
		
		// 以下は保存には必要ないが、カラムリスト画面で使う
		item.put( KEY_COLUMN_ACCESS, access_info.acct );
		item.put( KEY_COLUMN_NAME, getColumnName( true ) );
		item.put( KEY_OLD_INDEX, old_index );
	}
	
	Column( @NonNull ActMain activity, JSONObject src ){
		this.activity = activity;
		
		SavedAccount ac = SavedAccount.loadAccount( log, src.optLong( KEY_ACCOUNT_ROW_ID ) );
		if( ac == null ) throw new RuntimeException( "missing account" );
		this.access_info = ac;
		this.type = src.optInt( KEY_TYPE );
		this.with_attachment = src.optBoolean( KEY_WITH_ATTACHMENT );
		
		switch( type ){
		
		case TYPE_CONVERSATION:
			this.status_id = src.optLong( KEY_STATUS_ID );
			break;
		
		case TYPE_PROFILE:
			this.profile_id = src.optLong( KEY_PROFILE_ID );
			this.profile_tab = src.optInt( KEY_PROFILE_TAB );
			break;
		
		case TYPE_HASHTAG:
			this.hashtag = src.optString( KEY_HASHTAG );
			break;
		
		case TYPE_SEARCH:
			this.search_query = src.optString( KEY_SEARCH_QUERY );
			this.search_resolve = src.optBoolean( KEY_SEARCH_RESOLVE, false );
			break;
			
		}
		startLoading();
	}
	
	boolean isSameSpec( SavedAccount ai, int type, Object[] params ){
		if( type != this.type || ! Utils.equalsNullable( ai.acct, access_info.acct ) ) return false;
		switch( type ){
		default:
			return true;
		
		case TYPE_PROFILE:
			try{
				long who_id = (Long) getParamAt( params, 0 );
				return who_id == this.profile_id;
			}catch( Throwable ex ){
				return false;
			}
		
		case TYPE_CONVERSATION:
			try{
				long status_id = (Long) getParamAt( params, 0 );
				return status_id == this.status_id;
			}catch( Throwable ex ){
				return false;
			}
		
		case TYPE_HASHTAG:
			try{
				String hashtag = (String) getParamAt( params, 0 );
				return Utils.equalsNullable( hashtag, this.hashtag );
			}catch( Throwable ex ){
				return false;
			}
		
		case TYPE_SEARCH:
			try{
				String q = (String) getParamAt( params, 0 );
				boolean r = (Boolean) getParamAt( params, 1 );
				return Utils.equalsNullable( q, this.search_query )
					&& r == this.search_resolve;
			}catch( Throwable ex ){
				return false;
			}
			
		}
	}
	
	final AtomicBoolean is_dispose = new AtomicBoolean();
	
	void dispose(){
		is_dispose.set( true );
	}
	
	String getColumnName( boolean bLong ){
		switch( type ){
		
		default:
			return "?";
		
		case TYPE_HOME:
			return activity.getString( R.string.home );
		
		case TYPE_LOCAL:
			return activity.getString( R.string.local_timeline );
		
		case TYPE_FEDERATE:
			return activity.getString( R.string.federate_timeline );
		
		case TYPE_PROFILE:
			return activity.getString( R.string.statuses_of
				, who_account != null ? access_info.getFullAcct( who_account ) : Long.toString( profile_id )
			);
		
		case TYPE_FAVOURITES:
			return activity.getString( R.string.favourites );
		
		case TYPE_REPORTS:
			return activity.getString( R.string.reports );
		
		case TYPE_NOTIFICATIONS:
			return activity.getString( R.string.notifications );
		
		case TYPE_CONVERSATION:
			return activity.getString( R.string.conversation_around, status_id );
		
		case TYPE_HASHTAG:
			return activity.getString( R.string.hashtag_of, hashtag );
		
		case TYPE_MUTES:
			return activity.getString( R.string.muted_users );
		
		case TYPE_BLOCKS:
			return activity.getString( R.string.blocked_users );
		
		case TYPE_SEARCH:
			if( bLong ){
				return activity.getString( R.string.search_of, search_query );
			}else{
				return activity.getString( R.string.search );
			}
		}
	}
	
	interface StatusEntryCallback {
		void onIterate( TootStatus status );
	}
	
	// ブーストやお気に入りの更新に使う。ステータスを列挙する。
	void findStatus( SavedAccount target_account, long target_status_id, StatusEntryCallback callback ){
		if( target_account.acct.equals( access_info.acct ) ){
			for( int i = 0, ie = list_data.size() ; i < ie ; ++ i ){
				Object data = list_data.get( i );
				//
				if( data instanceof TootNotification ){
					data = ( (TootNotification) data ).status;
				}
				//
				if( data instanceof TootStatus ){
					//
					TootStatus status = (TootStatus) data;
					if( target_status_id == status.id ){
						callback.onIterate( status );
					}
					//
					TootStatus reblog = status.reblog;
					if( reblog != null ){
						if( target_status_id == reblog.id ){
							callback.onIterate( reblog );
						}
					}
				}
			}
		}
	}
	
	// ミュート、ブロックが成功した時に呼ばれる
	void removeStatusByAccount( SavedAccount target_account, long who_id ){
		if( ! target_account.acct.equals( access_info.acct ) ) return;
		
		ArrayList< Object > tmp_list = new ArrayList<>( list_data.size() );
		for( Object o : list_data ){
			if( o instanceof TootStatus ){
				TootStatus item = (TootStatus) o;
				if( item.account.id == who_id
					|| ( item.reblog != null && item.reblog.account.id == who_id )
					){
					continue;
				}
			}
			if( o instanceof TootNotification ){
				TootNotification item = (TootNotification) o;
				if( item.account.id == who_id ) continue;
				if( item.status != null ){
					if( item.status.account.id == who_id ) continue;
					if( item.status.reblog != null && item.status.reblog.account.id == who_id )
						continue;
				}
			}
			
			tmp_list.add( o );
		}
		list_data.clear();
		list_data.addAll( tmp_list );
	}
	
	// ミュート解除が成功した時に呼ばれる
	void removeFromMuteList( SavedAccount target_account, long who_id ){
		if( ! target_account.acct.equals( access_info.acct ) ) return;
		if( type != TYPE_MUTES ) return;
		
		ArrayList< Object > tmp_list = new ArrayList<>( list_data.size() );
		for( Object o : list_data ){
			if( o instanceof TootAccount ){
				TootAccount item = (TootAccount) o;
				if( item.id == who_id ) continue;
			}
			
			tmp_list.add( o );
		}
		list_data.clear();
		list_data.addAll( tmp_list );
	}
	
	// ミュート解除が成功した時に呼ばれる
	void removeFromBlockList( SavedAccount target_account, long who_id ){
		if( ! target_account.acct.equals( access_info.acct ) ) return;
		if( type != TYPE_BLOCKS ) return;
		
		ArrayList< Object > tmp_list = new ArrayList<>( list_data.size() );
		for( Object o : list_data ){
			if( o instanceof TootAccount ){
				TootAccount item = (TootAccount) o;
				if( item.id == who_id ) continue;
			}
			
			tmp_list.add( o );
		}
		list_data.clear();
		list_data.addAll( tmp_list );
	}
	
	// 自分のステータスを削除した時に呼ばれる
	void removeStatus( SavedAccount target_account, long status_id ){
		
		if( ! target_account.host.equals( access_info.host ) ) return;
		
		ArrayList< Object > tmp_list = new ArrayList<>( list_data.size() );
		for( Object o : list_data ){
			if( o instanceof TootStatus ){
				TootStatus item = (TootStatus) o;
				if( item.id == status_id
					|| ( item.reblog != null && item.reblog.id == status_id )
					){
					continue;
				}
			}
			if( o instanceof TootNotification ){
				TootNotification item = (TootNotification) o;
				if( item.status != null ){
					if( item.status.id == status_id ) continue;
					if( item.status.reblog != null && item.status.reblog.id == status_id )
						continue;
				}
			}
			
			tmp_list.add( o );
		}
		list_data.clear();
		list_data.addAll( tmp_list );
	}
	
	interface VisualCallback {
		void onVisualColumn();
	}
	
	private final LinkedList< VisualCallback > visual_callback = new LinkedList<>();
	
	void addVisualListener( VisualCallback listener ){
		if( listener == null ) return;
		for( VisualCallback vc : visual_callback ){
			if( vc == listener ) return;
		}
		visual_callback.add( listener );
	}
	
	void removeVisualListener( VisualCallback listener ){
		if( listener == null ) return;
		Iterator< VisualCallback > it = visual_callback.iterator();
		while( it.hasNext() ){
			VisualCallback vc = it.next();
			if( vc == listener ) it.remove();
		}
	}
	
	private final Runnable proc_fireVisualCallback = new Runnable() {
		@Override public void run(){
			for( VisualCallback aVisual_callback : visual_callback ){
				aVisual_callback.onVisualColumn();
			}
		}
	};
	
	void fireVisualCallback(){
		Utils.runOnMainThread( proc_fireVisualCallback );
	}
	
	private AsyncTask< Void, Void, TootApiResult > last_task;
	
	private void cancelLastTask(){
		if( last_task != null ){
			last_task.cancel( true );
			last_task = null;
			//
			bInitialLoading = false;
			bRefreshLoading = false;
			mInitialLoadingError = activity.getString( R.string.cancelled );
			//
		}
	}
	
	boolean bInitialLoading;
	boolean bRefreshLoading;
	
	String mInitialLoadingError;
	String mRefreshLoadingError;
	
	String task_progress;
	
	final ArrayList< Object > list_data = new ArrayList<>();
	
	void reload(){
		list_data.clear();
		startLoading();
	}
	
	private static boolean hasMedia( TootStatus status ){
		if( status == null ) return false;
		TootAttachment.List list = status.media_attachments;
		return ! ( list == null || list.isEmpty() );
	}
	
	private void startLoading(){
		cancelLastTask();
		
		mRefreshLoadingError = null;
		bRefreshLoading = false;
		mInitialLoadingError = null;
		bInitialLoading = true;
		max_id = null;
		since_id = null;
		
		fireVisualCallback();
		
		AsyncTask< Void, Void, TootApiResult > task = this.last_task = new AsyncTask< Void, Void, TootApiResult >() {
			
			TootApiResult parseAccount1( TootApiClient client, String path_base ){
				TootApiResult result = client.request( path_base );
				Column.this.who_account = TootAccount.parse( log, access_info, result.object );
				return result;
			}
			
			ArrayList< Object > list_tmp;
			
			TootApiResult getStatuses( TootApiClient client, String path_base ){
				long time_start = SystemClock.elapsedRealtime();
				TootApiResult result = client.request( path_base );
				if( result != null && result.array != null ){
					saveRange( result, true, true );
					//
					TootStatus.List src = TootStatus.parseList( log, access_info, result.array );
					list_tmp = new ArrayList<>( src.size() );
					if( ! with_attachment ){
						list_tmp.addAll( src );
					}else{
						for( TootStatus status : src ){
							if( hasMedia( status ) || hasMedia( status.reblog ) )
								list_tmp.add( status );
						}
					}
					//
					if( max_id != null && with_attachment ){
						char delimiter = ( - 1 != path_base.indexOf( '?' ) ? '&' : '?' );
						for( ; ; ){
							if( src.isEmpty() ){
								// 直前のリクエストが空のリストを返したら諦める
								break;
							}
							if( SystemClock.elapsedRealtime() - time_start > LOOP_TIMEOUT ){
								// タイムアウト
								break;
							}
							String path = path_base + delimiter + "max_id=" + max_id;
							TootApiResult result2 = client.request( path );
							if( result2 == null || result2.array == null ) break;
							if( ! saveRangeEnd( result2 ) ) break;
							
							src = TootStatus.parseList( log, access_info, result2.array );
							
							if( ! with_attachment ){
								list_tmp.addAll( src );
							}else{
								for( TootStatus status : src ){
									if( hasMedia( status ) || hasMedia( status.reblog ) )
										list_tmp.add( status );
								}
							}
						}
					}
				}
				return result;
			}
			
			TootApiResult parseAccountList( TootApiClient client, String path_base ){
				TootApiResult result = client.request( path_base );
				if( result != null ){
					saveRange( result, true, true );
					list_tmp = new ArrayList<>();
					list_tmp.addAll( TootAccount.parseList( log, access_info, result.array ) );
				}
				return result;
			}
			
			TootApiResult parseReports( TootApiClient client, String path_base ){
				TootApiResult result = client.request( path_base );
				if( result != null ){
					saveRange( result, true, true );
					list_tmp = new ArrayList<>();
					list_tmp.addAll( TootReport.parseList( log, result.array ) );
				}
				return result;
			}
			
			TootApiResult parseNotifications( TootApiClient client, String path_base ){
				TootApiResult result = client.request( path_base );
				if( result != null ){
					saveRange( result, true, true );
					TootNotification.List src = TootNotification.parseList( log, access_info, result.array );
					
					list_tmp = new ArrayList<>();
					list_tmp.addAll( src );
					//
					if( ! src.isEmpty() ){
						AlarmService.injectData( activity, access_info.db_id, src );
					}
					
				}
				return result;
			}
			
			@Override
			protected TootApiResult doInBackground( Void... params ){
				TootApiClient client = new TootApiClient( activity, new TootApiClient.Callback() {
					@Override
					public boolean isApiCancelled(){
						return isCancelled() || is_dispose.get();
					}
					
					@Override
					public void publishApiProgress( final String s ){
						Utils.runOnMainThread( new Runnable() {
							@Override
							public void run(){
								if( isCancelled() ) return;
								task_progress = s;
								fireVisualCallback();
							}
						} );
					}
				} );
				
				client.setAccount( access_info );
				
				TootApiResult result;
				
				switch( type ){
				
				default:
				case TYPE_HOME:
					return getStatuses( client, PATH_HOME );
				
				case TYPE_LOCAL:
					return getStatuses( client, PATH_LOCAL );
				
				case TYPE_FEDERATE:
					return getStatuses( client, PATH_FEDERATE );
				
				case TYPE_PROFILE:
					if( who_account == null ){
						parseAccount1( client, String.format( Locale.JAPAN, PATH_ACCOUNT, profile_id ) );
						client.callback.publishApiProgress( "" );
					}
					switch( profile_tab ){
					
					default:
					case TAB_STATUS:
						String s = String.format( Locale.JAPAN, PATH_ACCOUNT_STATUSES, profile_id );
						if( with_attachment ) s = s + "&only_media";
						return getStatuses( client, s );
					
					case TAB_FOLLOWING:
						return parseAccountList( client,
							String.format( Locale.JAPAN, PATH_ACCOUNT_FOLLOWING, profile_id ) );
					
					case TAB_FOLLOWERS:
						return parseAccountList( client,
							String.format( Locale.JAPAN, PATH_ACCOUNT_FOLLOWERS, profile_id ) );
						
					}
				
				case TYPE_MUTES:
					return parseAccountList( client, PATH_MUTES );
				
				case TYPE_BLOCKS:
					return parseAccountList( client, PATH_BLOCKS );
				
				case TYPE_FAVOURITES:
					return getStatuses( client, PATH_FAVOURITES );
				
				case TYPE_HASHTAG:
					return getStatuses( client,
						String.format( Locale.JAPAN, PATH_HASHTAG, Uri.encode( hashtag ) ) );
				
				case TYPE_REPORTS:
					return parseReports( client, PATH_REPORTS );
				
				case TYPE_NOTIFICATIONS:
					return parseNotifications( client, PATH_NOTIFICATIONS );
				
				case TYPE_CONVERSATION:
					
					// 指定された発言そのもの
					result = client.request(
						String.format( Locale.JAPAN, PATH_STATUSES, status_id ) );
					if( result == null || result.object == null ) return result;
					TootStatus target_status = TootStatus.parse( log, access_info, result.object );
					target_status.conversation_main = true;
					
					// 前後の会話
					result = client.request(
						String.format( Locale.JAPAN, PATH_STATUSES_CONTEXT, status_id ) );
					if( result == null || result.object == null ) return result;
					
					// 一つのリストにまとめる
					TootContext context = TootContext.parse( log, access_info, result.object );
					list_tmp = new ArrayList<>( 1 + context.ancestors.size() + context.descendants.size() );
					if( context.ancestors != null ) list_tmp.addAll( context.ancestors );
					list_tmp.add( target_status );
					if( context.descendants != null ) list_tmp.addAll( context.descendants );
					
					//
					return result;
				
				case TYPE_SEARCH:
					String path = String.format( Locale.JAPAN, PATH_SEARCH, Uri.encode( search_query ) );
					if( search_resolve ) path = path + "&resolve=1";
					
					result = client.request( path );
					if( result == null || result.object == null ) return result;
					
					TootResults tmp = TootResults.parse( log, access_info, result.object );
					if( tmp != null ){
						list_tmp = new ArrayList<>();
						list_tmp.addAll( tmp.hashtags );
						list_tmp.addAll( tmp.accounts );
						list_tmp.addAll( tmp.statuses );
					}
					return result;
					
				}
			}
			
			@Override
			protected void onCancelled( TootApiResult result ){
				onPostExecute( null );
			}
			
			@Override
			protected void onPostExecute( TootApiResult result ){
				
				if( isCancelled() || result == null ){
					return;
				}
				
				bInitialLoading = false;
				last_task = null;
				
				if( result.error != null ){
					Column.this.mInitialLoadingError = result.error;
				}else{
					
					if( list_tmp != null ){
						list_data.clear();
						list_data.addAll( list_tmp );
						
					}
					
				}
				fireVisualCallback();
			}
		};
		
		AsyncTaskCompat.executeParallel( task );
	}
	
	private static final Pattern reMaxId = Pattern.compile( "&max_id=(\\d+)" ); // より古いデータの取得に使う
	private static final Pattern reSinceId = Pattern.compile( "&since_id=(\\d+)" ); // より新しいデータの取得に使う
	
	private String max_id;
	private String since_id;
	int scroll_hack;
	
	private void saveRange( TootApiResult result, boolean bBottom, boolean bTop ){
		// Link:  <https://mastodon.juggler.jp/api/v1/timelines/home?limit=XX&max_id=405228>; rel="next",
		//        <https://mastodon.juggler.jp/api/v1/timelines/home?limit=XX&since_id=436946>; rel="prev"
		
		if( result.response != null ){
			String sv = result.response.header( "Link" );
			if( ! TextUtils.isEmpty( sv ) ){
				if( bBottom ){
					Matcher m = reMaxId.matcher( sv );
					if( m.find() ){
						max_id = m.group( 1 );
						//log.d( "col=%s,max_id=%s", this.hashCode(), max_id );
					}
				}
				if( bTop ){
					Matcher m = reSinceId.matcher( sv );
					if( m.find() ){
						since_id = m.group( 1 );
						//log.d( "col=%s,since_id=%s", this.hashCode(), since_id );
					}
				}
			}
		}
	}
	
	private boolean saveRangeEnd( TootApiResult result ){
		if( result.response != null ){
			String sv = result.response.header( "Link" );
			if( ! TextUtils.isEmpty( sv ) ){
				Matcher m = reMaxId.matcher( sv );
				if( m.find() ){
					max_id = m.group( 1 );
					//log.d( "col=%s,max_id=%s", this.hashCode(), max_id );
					return true;
				}
			}
		}
		return false;
	}
	
	private String addRange( boolean bBottom, String path ){
		char delm = ( - 1 != path.indexOf( '?' ) ? '&' : '?' );
		if( bBottom ){
			if( max_id != null ) return path + delm + "max_id=" + max_id;
		}else{
			if( since_id != null ) return path + delm + "since_id=" + since_id;
		}
		return path;
	}
	
	String startRefresh( final boolean bBottom ){
		if( last_task != null ){
			return activity.getString( R.string.column_is_busy );
		}else if( bBottom && max_id == null ){
			return activity.getString( R.string.end_of_list );
		}else if( ! bBottom && since_id == null ){
			return "startRefresh failed. missing since_id";
		}
		
		bRefreshLoading = true;
		mRefreshLoadingError = null;
		
		AsyncTask< Void, Void, TootApiResult > task = this.last_task = new AsyncTask< Void, Void, TootApiResult >() {
			
			TootApiResult parseAccount1( TootApiResult result ){
				if( result != null ){
					who_account = TootAccount.parse( log, access_info, result.object );
				}
				return result;
			}
			
			TootApiResult getAccountList( TootApiClient client, String path_base ){
				long time_start = SystemClock.elapsedRealtime();
				char delimiter = ( - 1 != path_base.indexOf( '?' ) ? '&' : '?' );
				String last_since_id = since_id;
				
				TootApiResult result = client.request( addRange( bBottom, path_base ) );
				if( result != null && result.array != null ){
					saveRange( result, bBottom, ! bBottom );
					list_tmp = new ArrayList<>();
					TootAccount.List src = TootAccount.parseList( log, access_info, result.array );
					list_tmp.addAll( src );
					if( ! bBottom ){
						for( ; ; ){
							// max_id だけを指定した場合、必ずlimit個のデータが帰ってくるとは限らない
							// 直前のデータが0個なら終了とみなすしかなさそう
							if( src.isEmpty() ){
								log.d( "refresh-account-top: previous size == 0." );
								break;
							}
							
							// 隙間の最新のステータスIDは取得データ末尾のステータスIDである
							String max_id = Long.toString( src.get( src.size() - 1 ).id );
							
							if( SystemClock.elapsedRealtime() - time_start > LOOP_TIMEOUT ){
								// タイムアウト
								// 隙間ができるかもしれない。後ほど手動で試してもらうしかない
								TootGap gap = new TootGap( max_id, last_since_id );
								list_tmp.add( gap );
								break;
							}
							
							String path = path_base + delimiter + "max_id=" + max_id + "&since_id=" + last_since_id;
							TootApiResult result2 = client.request( path );
							if( result2 == null || result2.array == null ){
								// エラー
								// 隙間ができるかもしれない。後ほど手動で試してもらうしかない
								TootGap gap = new TootGap( max_id, last_since_id );
								list_tmp.add( gap );
								break;
							}
							
							src = TootAccount.parseList( log, access_info, result2.array );
							list_tmp.addAll( src );
						}
					}
				}
				return result;
			}
			
			TootApiResult getReportList( TootApiClient client, String path_base ){
				long time_start = SystemClock.elapsedRealtime();
				char delimiter = ( - 1 != path_base.indexOf( '?' ) ? '&' : '?' );
				String last_since_id = since_id;
				TootApiResult result = client.request( addRange( bBottom, path_base ) );
				if( result != null && result.array != null ){
					saveRange( result, bBottom, ! bBottom );
					list_tmp = new ArrayList<>();
					TootReport.List src = TootReport.parseList( log, result.array );
					list_tmp.addAll( src );
					if( ! bBottom ){
						for( ; ; ){
							// max_id だけを指定した場合、必ずlimit個のデータが帰ってくるとは限らない
							// 直前のデータが0個なら終了とみなすしかなさそう
							if( src.isEmpty() ){
								log.d( "refresh-report-top: previous size == 0." );
								break;
							}
							
							// 隙間の最新のステータスIDは取得データ末尾のステータスIDである
							String max_id = Long.toString( src.get( src.size() - 1 ).id );
							
							if( SystemClock.elapsedRealtime() - time_start > LOOP_TIMEOUT ){
								// タイムアウト
								// 隙間ができるかもしれない。後ほど手動で試してもらうしかない
								TootGap gap = new TootGap( max_id, last_since_id );
								list_tmp.add( gap );
								break;
							}
							
							String path = path_base + delimiter + "max_id=" + max_id + "&since_id=" + last_since_id;
							TootApiResult result2 = client.request( path );
							if( result2 == null || result2.array == null ){
								// エラー
								// 隙間ができるかもしれない。後ほど手動で試してもらうしかない
								TootGap gap = new TootGap( max_id, last_since_id );
								list_tmp.add( gap );
								break;
							}
							
							src = TootReport.parseList( log, result2.array );
							list_tmp.addAll( src );
						}
					}
				}
				return result;
			}
			
			TootApiResult getNotificationList( TootApiClient client, String path_base ){
				long time_start = SystemClock.elapsedRealtime();
				char delimiter = ( - 1 != path_base.indexOf( '?' ) ? '&' : '?' );
				String last_since_id = since_id;
				
				TootApiResult result = client.request( addRange( bBottom, path_base ) );
				if( result != null && result.array != null ){
					saveRange( result, bBottom, ! bBottom );
					list_tmp = new ArrayList<>();
					TootNotification.List src = TootNotification.parseList( log, access_info, result.array );
					list_tmp.addAll( src );
					
					if( ! src.isEmpty() ){
						AlarmService.injectData( activity, access_info.db_id, src );
					}
					
					if( ! bBottom ){
						for( ; ; ){
							// max_id だけを指定した場合、必ずlimit個のデータが帰ってくるとは限らない
							// 直前のデータが0個なら終了とみなすしかなさそう
							if( src.isEmpty() ){
								log.d( "refresh-notification-top: previous size == 0." );
								break;
							}
							
							// 隙間の最新のステータスIDは取得データ末尾のステータスIDである
							String max_id = Long.toString( src.get( src.size() - 1 ).id );
							
							if( SystemClock.elapsedRealtime() - time_start > LOOP_TIMEOUT ){
								// タイムアウト
								// 隙間ができるかもしれない。後ほど手動で試してもらうしかない
								TootGap gap = new TootGap( max_id, last_since_id );
								list_tmp.add( gap );
								break;
							}
							
							String path = path_base + delimiter + "max_id=" + max_id + "&since_id=" + last_since_id;
							TootApiResult result2 = client.request( path );
							if( result2 == null || result2.array == null ){
								// エラー
								// 隙間ができるかもしれない。後ほど手動で試してもらうしかない
								TootGap gap = new TootGap( max_id, last_since_id );
								list_tmp.add( gap );
								break;
							}
							
							src = TootNotification.parseList( log, access_info, result2.array );
							if( ! src.isEmpty() ){
								list_tmp.addAll( src );
								AlarmService.injectData( activity, access_info.db_id, src );
							}
						}
					}
				}
				return result;
			}
			
			ArrayList< Object > list_tmp;
			
			TootApiResult getStatusList( TootApiClient client, String path_base ){
				long time_start = SystemClock.elapsedRealtime();
				
				char delimiter = ( - 1 != path_base.indexOf( '?' ) ? '&' : '?' );
				final String last_since_id = since_id;
				
				TootApiResult result = client.request( addRange( bBottom, path_base ) );
				if( result != null && result.array != null ){
					saveRange( result, bBottom, ! bBottom );
					TootStatus.List src = TootStatus.parseList( log, access_info, result.array );
					list_tmp = new ArrayList<>();
					
					if( ! with_attachment ){
						list_tmp.addAll( src );
					}else{
						for( TootStatus status : src ){
							if( hasMedia( status ) || hasMedia( status.reblog ) )
								list_tmp.add( status );
						}
					}
					
					if( bBottom ){
						if( with_attachment ){
							for( ; ; ){
								// max_id だけを指定した場合、必ずlimit個のデータが帰ってくるとは限らない
								// 直前のデータが0個なら終了とみなすしかなさそう
								if( src.isEmpty() ){
									log.d( "refresh-status-bottom: previous size == 0." );
									break;
								}
								
								if( SystemClock.elapsedRealtime() - time_start > LOOP_TIMEOUT ){
									// タイムアウト
									log.d( "refresh-status-bottom: loop timeout." );
									break;
								}
								
								String path = path_base + delimiter + "max_id=" + max_id;
								TootApiResult result2 = client.request( path );
								if( result2 == null || result2.array == null ){
									log.d( "refresh-status-bottom: error or cancelled." );
									break;
								}
								
								src = TootStatus.parseList( log, access_info, result2.array );
								
								if( ! with_attachment ){
									list_tmp.addAll( src );
								}else{
									for( TootStatus status : src ){
										if( hasMedia( status ) || hasMedia( status.reblog ) )
											list_tmp.add( status );
									}
								}
								
								if( ! saveRangeEnd( result2 ) ){
									log.d( "refresh-status-bottom: saveRangeEnd failed." );
									break;
								}
							}
						}
					}else{
						for( ; ; ){
							// max_id だけを指定した場合、必ずlimit個のデータが帰ってくるとは限らない
							// 直前のデータが0個なら終了とみなすしかなさそう
							if( src.isEmpty() ){
								log.d( "refresh-status-top: previous size == 0." );
								break;
							}
							
							// 隙間の最新のステータスIDは取得データ末尾のステータスIDである
							String max_id = Long.toString( src.get( src.size() - 1 ).id );
							
							if( SystemClock.elapsedRealtime() - time_start > LOOP_TIMEOUT ){
								// タイムアウト
								// 隙間ができるかもしれない。後ほど手動で試してもらうしかない
								TootGap gap = new TootGap( max_id, last_since_id );
								list_tmp.add( gap );
								break;
							}
							
							String path = path_base + delimiter + "max_id=" + max_id + "&since_id=" + last_since_id;
							TootApiResult result2 = client.request( path );
							if( result2 == null || result2.array == null ){
								// エラー
								// 隙間ができるかもしれない。後ほど手動で試してもらうしかない
								TootGap gap = new TootGap( max_id, last_since_id );
								list_tmp.add( gap );
								break;
							}
							
							src = TootStatus.parseList( log, access_info, result2.array );
							if( ! with_attachment ){
								list_tmp.addAll( src );
							}else{
								for( TootStatus status : src ){
									if( hasMedia( status ) || hasMedia( status.reblog ) )
										list_tmp.add( status );
								}
							}
						}
					}
				}
				return result;
			}
			
			@Override
			protected TootApiResult doInBackground( Void... params ){
				TootApiClient client = new TootApiClient( activity, new TootApiClient.Callback() {
					@Override
					public boolean isApiCancelled(){
						return isCancelled() || is_dispose.get();
					}
					
					@Override
					public void publishApiProgress( final String s ){
						Utils.runOnMainThread( new Runnable() {
							@Override
							public void run(){
								if( isCancelled() ) return;
								task_progress = s;
								fireVisualCallback();
							}
						} );
					}
				} );
				
				client.setAccount( access_info );
				
				switch( type ){
				
				default:
				case TYPE_HOME:
					return getStatusList( client, PATH_HOME );
				
				case TYPE_LOCAL:
					return getStatusList( client, PATH_LOCAL );
				
				case TYPE_FEDERATE:
					return getStatusList( client, PATH_FEDERATE );
				
				case TYPE_FAVOURITES:
					return getStatusList( client, PATH_FAVOURITES );
				
				case TYPE_REPORTS:
					return getReportList( client, PATH_REPORTS );
				
				case TYPE_NOTIFICATIONS:
					return getNotificationList( client, PATH_NOTIFICATIONS );
				
				case TYPE_PROFILE:
					if( who_account == null ){
						parseAccount1( client.request(
							String.format( Locale.JAPAN, PATH_ACCOUNT, profile_id ) ) );
						
						client.callback.publishApiProgress( "" );
					}
					switch( profile_tab ){
					
					default:
					case TAB_STATUS:
						return getStatusList( client,
							String.format( Locale.JAPAN, PATH_ACCOUNT_STATUSES, profile_id ) );
					
					case TAB_FOLLOWING:
						return getAccountList( client,
							String.format( Locale.JAPAN, PATH_ACCOUNT_FOLLOWING, profile_id ) );
					
					case TAB_FOLLOWERS:
						return getAccountList( client,
							String.format( Locale.JAPAN, PATH_ACCOUNT_FOLLOWERS, profile_id ) );
						
					}
				case TYPE_MUTES:
					return getAccountList( client, PATH_MUTES );
				
				case TYPE_BLOCKS:
					return getAccountList( client, PATH_BLOCKS );
				
				case TYPE_HASHTAG:
					return getStatusList( client,
						String.format( Locale.JAPAN, PATH_HASHTAG, Uri.encode( hashtag ) ) );
				}
			}
			
			@Override
			protected void onCancelled( TootApiResult result ){
				onPostExecute( null );
			}
			
			@Override
			protected void onPostExecute( TootApiResult result ){
				
				if( isCancelled() || result == null ){
					return;
				}
				last_task = null;
				bRefreshLoading = false;
				
				if( result.error != null ){
					Column.this.mRefreshLoadingError = result.error;
				}else{
					if( list_tmp != null ){
						// 古いリストにある要素のIDの集合集合
						HashSet< Long > set_status_id = new HashSet<>();
						HashSet< Long > set_notification_id = new HashSet<>();
						HashSet< Long > set_report_id = new HashSet<>();
						HashSet< Long > set_account_id = new HashSet<>();
						for( Object o : list_data ){
							if( o instanceof TootStatus ){
								set_status_id.add( ( (TootStatus) o ).id );
							}else if( o instanceof TootNotification ){
								set_notification_id.add( ( (TootNotification) o ).id );
							}else if( o instanceof TootReport ){
								set_report_id.add( ( (TootReport) o ).id );
							}else if( o instanceof TootAccount ){
								set_account_id.add( ( (TootAccount) o ).id );
							}
						}
						ArrayList< Object > list_new = new ArrayList<>();
						for( Object o : list_tmp ){
							if( o instanceof TootStatus ){
								if( set_status_id.contains( ( (TootStatus) o ).id ) ) continue;
							}else if( o instanceof TootNotification ){
								if( set_notification_id.contains( ( (TootNotification) o ).id ) )
									continue;
							}else if( o instanceof TootReport ){
								if( set_report_id.contains( ( (TootReport) o ).id ) ) continue;
							}else if( o instanceof TootAccount ){
								if( set_account_id.contains( ( (TootAccount) o ).id ) ) continue;
							}
							list_new.add( o );
						}
						
						if( ! bBottom ){
							// リフレッシュ開始時はリストの先頭を見ていたのだからスクロール範囲を調整したい
							scroll_hack = list_new.size();
							// 新しいデータの後に今のデータが並ぶ
							list_new.addAll( list_data );
							list_data.clear();
							list_data.addAll( list_new );
						}else{
							// 今のデータの後にさらに古いデータが続く
							list_data.addAll( list_new );
						}
						
					}
				}
				
				fireVisualCallback();
			}
		};
		
		AsyncTaskCompat.executeParallel( task );
		
		return null;
	}
	
	String startGap( final TootGap gap ){
		if( last_task != null ){
			return activity.getString( R.string.column_is_busy );
		}
		
		bRefreshLoading = true;
		mRefreshLoadingError = null;
		
		AsyncTask< Void, Void, TootApiResult > task = this.last_task = new AsyncTask< Void, Void, TootApiResult >() {
			String max_id = gap.max_id;
			String since_id = gap.since_id;
			ArrayList< Object > list_tmp;
			
			TootApiResult getAccountList( TootApiClient client, String path_base ){
				long time_start = SystemClock.elapsedRealtime();
				char delimiter = ( - 1 != path_base.indexOf( '?' ) ? '&' : '?' );
				list_tmp = new ArrayList<>();
				
				TootApiResult result = null;
				for( ; ; ){
					if( result != null && SystemClock.elapsedRealtime() - time_start > LOOP_TIMEOUT ){
						// タイムアウト
						// 隙間が残る
						list_tmp.add( new TootGap( max_id, since_id ) );
						break;
					}
					
					String path = path_base + delimiter + "max_id=" + max_id + "&since_id=" + since_id;
					TootApiResult r2 = client.request( path );
					if( r2 == null || r2.array == null ){
						if( result == null ) result = r2;
						// 隙間が残る
						list_tmp.add( new TootGap( max_id, since_id ) );
						break;
					}
					result = r2;
					TootAccount.List src = TootAccount.parseList( log, access_info, r2.array );
					
					if( src.isEmpty() ){
						break;
					}
					
					// 隙間の最新のステータスIDは取得データ末尾のステータスIDである
					max_id = Long.toString( src.get( src.size() - 1 ).id );
					
					list_tmp.addAll( src );
				}
				return result;
			}
			
			TootApiResult getReportList( TootApiClient client, String path_base ){
				long time_start = SystemClock.elapsedRealtime();
				char delimiter = ( - 1 != path_base.indexOf( '?' ) ? '&' : '?' );
				list_tmp = new ArrayList<>();
				
				TootApiResult result = null;
				for( ; ; ){
					if( result != null && SystemClock.elapsedRealtime() - time_start > LOOP_TIMEOUT ){
						// タイムアウト
						// 隙間が残る
						list_tmp.add( new TootGap( max_id, since_id ) );
						break;
					}
					
					String path = path_base + delimiter + "max_id=" + max_id + "&since_id=" + since_id;
					TootApiResult r2 = client.request( path );
					if( r2 == null || r2.array == null ){
						if( result == null ) result = r2;
						// 隙間が残る
						list_tmp.add( new TootGap( max_id, since_id ) );
						break;
					}
					
					result = r2;
					TootReport.List src = TootReport.parseList( log, r2.array );
					if( src.isEmpty() ){
						// コレ以上取得する必要はない
						break;
					}
					
					// 隙間の最新のステータスIDは取得データ末尾のステータスIDである
					max_id = Long.toString( src.get( src.size() - 1 ).id );
					
					list_tmp.addAll( src );
				}
				return result;
			}
			
			TootApiResult getNotificationList( TootApiClient client, String path_base ){
				long time_start = SystemClock.elapsedRealtime();
				char delimiter = ( - 1 != path_base.indexOf( '?' ) ? '&' : '?' );
				list_tmp = new ArrayList<>();
				
				TootApiResult result = null;
				for( ; ; ){
					if( result != null && SystemClock.elapsedRealtime() - time_start > LOOP_TIMEOUT ){
						// タイムアウト
						// 隙間が残る
						list_tmp.add( new TootGap( max_id, since_id ) );
						break;
					}
					String path = path_base + delimiter + "max_id=" + max_id + "&since_id=" + since_id;
					TootApiResult r2 = client.request( path );
					if( r2 == null || r2.array == null ){
						// エラー
						log.d( "gap-notification: got error." );
						
						if( result == null ) result = r2;
						
						// 隙間が残る
						list_tmp.add( new TootGap( max_id, since_id ) );
						break;
					}
					
					result = r2;
					TootNotification.List src = TootNotification.parseList( log, access_info, r2.array );
					
					if( src.isEmpty() ){
						log.d( "gap-notification: got empty list." );
						break;
					}
					
					// 隙間の最新のステータスIDは取得データ末尾のステータスIDである
					max_id = Long.toString( src.get( src.size() - 1 ).id );
					
					list_tmp.addAll( src );
					
					AlarmService.injectData( activity, access_info.db_id, src );
					
				}
				return result;
			}
			
			TootApiResult getStatusList( TootApiClient client, String path_base ){
				long time_start = SystemClock.elapsedRealtime();
				char delimiter = ( - 1 != path_base.indexOf( '?' ) ? '&' : '?' );
				list_tmp = new ArrayList<>();
				
				TootApiResult result = null;
				for( ; ; ){
					if( result != null && SystemClock.elapsedRealtime() - time_start > LOOP_TIMEOUT ){
						// タイムアウト
						// 隙間が残る
						list_tmp.add( new TootGap( max_id, since_id ) );
						break;
					}
					
					String path = path_base + delimiter + "max_id=" + max_id + "&since_id=" + since_id;
					
					TootApiResult r2 = client.request( path );
					if( r2 == null || r2.array == null ){
						// エラー
						log.d( "gap-status: got error." );
						
						// 成功データがない場合だけ、今回のエラーを返すようにする
						if( result == null ) result = r2;
						
						// 隙間が残る
						list_tmp.add( new TootGap( max_id, since_id ) );
						
						break;
					}
					
					// 成功した場合はそれを返したい
					result = r2;
					
					TootStatus.List src = TootStatus.parseList( log, access_info, r2.array );
					if( src.size() == 0 ){
						// 直前の取得でカラのデータが帰ってきたら終了
						log.d( "gap-status: got empty list." );
						break;
					}
					// 隙間の最新のステータスIDは取得データ末尾のステータスIDである
					max_id = Long.toString( src.get( src.size() - 1 ).id );
					
					if( ! with_attachment ){
						list_tmp.addAll( src );
					}else{
						for( TootStatus status : src ){
							if( hasMedia( status ) || hasMedia( status.reblog ) )
								list_tmp.add( status );
						}
					}
				}
				return result;
			}
			
			@Override protected TootApiResult doInBackground( Void... params ){
				TootApiClient client = new TootApiClient( activity, new TootApiClient.Callback() {
					@Override public boolean isApiCancelled(){
						return isCancelled() || is_dispose.get();
					}
					
					@Override public void publishApiProgress( final String s ){
						Utils.runOnMainThread( new Runnable() {
							@Override
							public void run(){
								if( isCancelled() ) return;
								task_progress = s;
								fireVisualCallback();
							}
						} );
					}
				} );
				
				client.setAccount( access_info );
				
				switch( type ){
				
				default:
				case TYPE_HOME:
					return getStatusList( client, PATH_HOME );
				
				case TYPE_LOCAL:
					return getStatusList( client, PATH_LOCAL );
				
				case TYPE_FEDERATE:
					return getStatusList( client, PATH_FEDERATE );
				
				case TYPE_FAVOURITES:
					return getStatusList( client, PATH_FAVOURITES );
				
				case TYPE_REPORTS:
					return getReportList( client, PATH_REPORTS );
				
				case TYPE_NOTIFICATIONS:
					return getNotificationList( client, PATH_NOTIFICATIONS );
				
				case TYPE_HASHTAG:
					return getStatusList( client,
						String.format( Locale.JAPAN, PATH_HASHTAG, Uri.encode( hashtag ) ) );
				
				case TYPE_MUTES:
					return getAccountList( client, PATH_MUTES );
				
				case TYPE_BLOCKS:
					return getAccountList( client, PATH_BLOCKS );
				
				case TYPE_PROFILE:
					switch( profile_tab ){
					
					default:
					case TAB_STATUS:
						return getStatusList( client,
							String.format( Locale.JAPAN, PATH_ACCOUNT_STATUSES, profile_id ) );
					
					case TAB_FOLLOWING:
						return getAccountList( client,
							String.format( Locale.JAPAN, PATH_ACCOUNT_FOLLOWING, profile_id ) );
					
					case TAB_FOLLOWERS:
						return getAccountList( client,
							String.format( Locale.JAPAN, PATH_ACCOUNT_FOLLOWERS, profile_id ) );
					}
					
				}
			}
			
			@Override
			protected void onCancelled( TootApiResult result ){
				onPostExecute( null );
			}
			
			@Override
			protected void onPostExecute( TootApiResult result ){
				
				if( isCancelled() || result == null ){
					return;
				}
				last_task = null;
				bRefreshLoading = false;
				
				if( result.error != null ){
					Column.this.mRefreshLoadingError = result.error;
				}else{
					if( list_tmp != null ){
						// 古いリストにある要素のIDの集合
						HashSet< Long > set_status_id = new HashSet<>();
						HashSet< Long > set_notification_id = new HashSet<>();
						HashSet< Long > set_report_id = new HashSet<>();
						HashSet< Long > set_account_id = new HashSet<>();
						for( Object o : list_data ){
							if( o instanceof TootStatus ){
								set_status_id.add( ( (TootStatus) o ).id );
							}else if( o instanceof TootNotification ){
								set_notification_id.add( ( (TootNotification) o ).id );
							}else if( o instanceof TootReport ){
								set_report_id.add( ( (TootReport) o ).id );
							}else if( o instanceof TootAccount ){
								set_account_id.add( ( (TootAccount) o ).id );
							}
						}
						// list_tmp をフィルタしてlist_newを作成
						ArrayList< Object > list_new = new ArrayList<>();
						for( Object o : list_tmp ){
							if( o instanceof TootStatus ){
								if( set_status_id.contains( ( (TootStatus) o ).id ) ) continue;
							}else if( o instanceof TootNotification ){
								if( set_notification_id.contains( ( (TootNotification) o ).id ) )
									continue;
							}else if( o instanceof TootReport ){
								if( set_report_id.contains( ( (TootReport) o ).id ) ) continue;
							}else if( o instanceof TootAccount ){
								if( set_account_id.contains( ( (TootAccount) o ).id ) )
									continue;
							}
							list_new.add( o );
						}
						
						int pos = list_data.indexOf( gap );
						if( pos != - 1 ){
							list_data.remove( pos );
							list_data.addAll( pos, list_new );
						}
					}
				}
				
				fireVisualCallback();
			}
		};
		
		AsyncTaskCompat.executeParallel( task );
		return null;
	}
}
