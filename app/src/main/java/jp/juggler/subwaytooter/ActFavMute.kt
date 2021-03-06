package jp.juggler.subwaytooter

import android.content.Context
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import com.woxthebox.draglistview.DragItem
import com.woxthebox.draglistview.DragItemAdapter
import com.woxthebox.draglistview.DragListView
import com.woxthebox.draglistview.swipe.ListSwipeHelper
import com.woxthebox.draglistview.swipe.ListSwipeItem

import java.util.ArrayList

import jp.juggler.subwaytooter.table.FavMute
import jp.juggler.subwaytooter.util.LogCategory

class ActFavMute : AppCompatActivity() {
	
	companion object {
		
		private val log = LogCategory("ActFavMute")
	}
	
	private lateinit var listView : DragListView
	private lateinit var listAdapter : MyListAdapter
	
	override fun onCreate(savedInstanceState : Bundle?) {
		super.onCreate(savedInstanceState)
		App1.setActivityTheme(this, false)
		initUI()
		loadData()
	}
	
	override fun onBackPressed() {
		setResult(RESULT_OK)
		super.onBackPressed()
	}
	
	private fun initUI() {
		setContentView(R.layout.act_word_list)
		
		Styler.fixHorizontalPadding2(findViewById(R.id.llContent))
		
		// リストのアダプター
		listAdapter = MyListAdapter()
		
		// ハンドル部分をドラッグで並べ替えできるRecyclerView
		listView = findViewById(R.id.drag_list_view)
		listView.setLayoutManager(LinearLayoutManager(this))
		listView.setAdapter(listAdapter, false)
		
		listView.setCanDragHorizontally(true)
		listView.isDragEnabled = false
		listView.setCustomDragItem(MyDragItem(this, R.layout.lv_mute_app))
		
		listView.recyclerView.isVerticalScrollBarEnabled = true
		//		listView.setDragListListener( new DragListView.DragListListenerAdapter() {
		//			@Override
		//			public void onItemDragStarted( int position ){
		//				// 操作中はリフレッシュ禁止
		//				// mRefreshLayout.setEnabled( false );
		//			}
		//
		//			@Override
		//			public void onItemDragEnded( int fromPosition, int toPosition ){
		//				// 操作完了でリフレッシュ許可
		//				// mRefreshLayout.setEnabled( USE_SWIPE_REFRESH );
		//
		////				if( fromPosition != toPosition ){
		////					// 並べ替えが発生した
		////				}
		//			}
		//		} );
		
		// リストを左右スワイプした
		listView.setSwipeListener(object : ListSwipeHelper.OnSwipeListenerAdapter() {
			
			override fun onItemSwipeStarted(item : ListSwipeItem?) {
				// 操作中はリフレッシュ禁止
				// mRefreshLayout.setEnabled( false );
			}
			
			override fun onItemSwipeEnded(item : ListSwipeItem?, swipedDirection : ListSwipeItem.SwipeDirection?) {
				// 操作完了でリフレッシュ許可
				// mRefreshLayout.setEnabled( USE_SWIPE_REFRESH );
				
				// 左にスワイプした(右端に青が見えた) なら要素を削除する
				if(swipedDirection == ListSwipeItem.SwipeDirection.LEFT) {
					val o = item ?.tag
					if(o is MyItem) {
						FavMute.delete(o.name)
						listAdapter.removeItem(listAdapter.getPositionForItem(o))
					}
				}
			}
		})
	}
	
	private fun loadData() {
		
		val tmp_list = ArrayList<MyItem>()
		try {
			FavMute.createCursor().use { cursor ->
				val idx_id = cursor.getColumnIndex(FavMute.COL_ID)
				val idx_name = cursor.getColumnIndex(FavMute.COL_ACCT)
				while(cursor.moveToNext()) {
					val id = cursor.getLong(idx_id)
					val name = cursor.getString(idx_name)
					val item = MyItem(id, name)
					tmp_list.add(item)
				}
			}
		} catch(ex : Throwable) {
			log.trace(ex)
		}
		
		listAdapter.itemList = tmp_list
	}
	
	// リスト要素のデータ
	internal class MyItem(val id : Long, val name : String)
	
	// リスト要素のViewHolder
	internal class MyViewHolder(viewRoot : View) : DragItemAdapter.ViewHolder(viewRoot, R.id.ivDragHandle, false) {
		
		val tvName : TextView
		
		init {
			
			tvName = viewRoot.findViewById(R.id.tvName)
			
			// リスト要素のビューが ListSwipeItem だった場合、Swipe操作を制御できる
			if(viewRoot is ListSwipeItem) {
				viewRoot.setSwipeInStyle(ListSwipeItem.SwipeInStyle.SLIDE)
				viewRoot.supportedSwipeDirection = ListSwipeItem.SwipeDirection.LEFT
			}
			
		} // View ID。 ここを押すとドラッグ操作をすぐに開始する
		// 長押しでドラッグ開始するなら真
		
		fun bind(item : MyItem) {
			itemView.tag = item // itemView は親クラスのメンバ変数
			tvName.text = item.name
		}
		
		//		@Override
		//		public boolean onItemLongClicked( View view ){
		//			return false;
		//		}
		
		//		@Override
		//		public void onItemClicked( View view ){
		//		}
	}
	
	// ドラッグ操作中のデータ
	private inner class MyDragItem internal constructor(context : Context, layoutId : Int) : DragItem(context, layoutId) {
		
		override fun onBindDragView(clickedView : View, dragView : View) {
			dragView.findViewById<TextView>(R.id.tvName).text = clickedView.findViewById<TextView>(R.id.tvName).text
			
			dragView.findViewById<View>(R.id.item_layout).setBackgroundColor(
				Styler.getAttributeColor(this@ActFavMute, R.attr.list_item_bg_pressed_dragged)
			)
		}
	}
	
	private inner class MyListAdapter internal constructor() : DragItemAdapter<MyItem, MyViewHolder>() {
		
		init {
			setHasStableIds(true)
			itemList = ArrayList()
		}
		
		override fun onCreateViewHolder(parent : ViewGroup, viewType : Int) : MyViewHolder {
			val view = layoutInflater.inflate(R.layout.lv_mute_app, parent, false)
			return MyViewHolder(view)
		}
		
		override fun onBindViewHolder(holder : MyViewHolder, position : Int) {
			super.onBindViewHolder(holder, position)
			holder.bind(itemList[position])
		}
		
		override fun getUniqueItemId(position : Int) : Long {
			val item = mItemList[position] // mItemList は親クラスのメンバ変数
			return item.id
		}
	}
	
}
