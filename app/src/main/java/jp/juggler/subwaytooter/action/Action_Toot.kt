package jp.juggler.subwaytooter.action

import android.net.Uri
import jp.juggler.subwaytooter.*
import jp.juggler.subwaytooter.api.*
import jp.juggler.subwaytooter.api.entity.EntityId
import jp.juggler.subwaytooter.api.entity.EntityIdLong
import jp.juggler.subwaytooter.api.entity.TootStatus
import jp.juggler.subwaytooter.api.entity.TootVisibility
import jp.juggler.subwaytooter.dialog.AccountPicker
import jp.juggler.subwaytooter.dialog.ActionsDialog
import jp.juggler.subwaytooter.dialog.DlgConfirm
import jp.juggler.subwaytooter.table.AcctColor
import jp.juggler.subwaytooter.table.SavedAccount
import jp.juggler.subwaytooter.util.EmptyCallback
import jp.juggler.subwaytooter.util.LogCategory
import jp.juggler.subwaytooter.util.showToast
import jp.juggler.subwaytooter.util.toPostRequestBuilder
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.*
import java.util.regex.Pattern

object Action_Toot {
	
	private val log = LogCategory("Action_Toot")
	
	private val reDetailedStatusTime =
		Pattern.compile("<a\\b[^>]*?\\bdetailed-status__datetime\\b[^>]*href=\"https://[^/]+/@[^/]+/(\\d+)\"")
	
	// アカウントを選んでお気に入り
	fun favouriteFromAnotherAccount(
		activity : ActMain,
		timeline_account : SavedAccount,
		status : TootStatus?
	) {
		if(status == null) return
		val who_host = timeline_account.host
		
		AccountPicker.pick(
			activity,
			bAllowPseudo = false,
			bAuto = false,
			message = activity.getString(R.string.account_picker_favourite),
			accountListArg = makeAccountListNonPseudo(activity, who_host)
		) { action_account ->
			favourite(
				activity,
				action_account,
				status,
				calcCrossAccountMode(timeline_account, action_account),
				callback = activity.favourite_complete_callback
			)
		}
	}
	
	// お気に入りの非同期処理
	fun favourite(
		activity : ActMain,
		access_info : SavedAccount,
		arg_status : TootStatus,
		nCrossAccountMode : Int,
		callback : EmptyCallback?,
		bSet : Boolean = true,
		bConfirmed : Boolean = false
	) {
		if(App1.getAppState(activity).isBusyFav(access_info, arg_status)) {
			showToast(activity, false, R.string.wait_previous_operation)
			return
		}
		
		// 必要なら確認を出す
		if(! bConfirmed && !access_info.isMisskey) {
			DlgConfirm.open(
				activity,
				activity.getString(
					when(bSet) {
						true -> R.string.confirm_favourite_from
						else -> R.string.confirm_unfavourite_from
					},
					AcctColor.getNickname(access_info.acct)
				),
				object : DlgConfirm.Callback {
					
					override fun onOK() {
						favourite(
							activity,
							access_info,
							arg_status,
							nCrossAccountMode,
							callback,
							bSet = bSet,
							bConfirmed = true
						)
					}
					
					override var isConfirmEnabled : Boolean
						get() = when(bSet) {
							true -> access_info.confirm_favourite
							else -> access_info.confirm_unfavourite
							
						}
						set(value) {
							when(bSet) {
								true -> access_info.confirm_favourite = value
								else -> access_info.confirm_unfavourite = value
							}
							access_info.saveSetting()
							activity.reloadAccountSetting(access_info)
						}
					
				})
			return
		}
		
		//
		App1.getAppState(activity).setBusyFav(access_info, arg_status)
		
		//
		TootTaskRunner(activity, TootTaskRunner.PROGRESS_NONE).run(access_info, object : TootTask {
			
			var new_status : TootStatus? = null
			override fun background(client : TootApiClient) : TootApiResult? {
				
				var result : TootApiResult?
				
				val target_status : TootStatus
				if(nCrossAccountMode == CROSS_ACCOUNT_REMOTE_INSTANCE) {
					
					result = client.syncStatus( access_info,arg_status)
					if( result?.data == null)  return result
					target_status = result.data as? TootStatus
						?: return TootApiResult(activity.getString(R.string.status_id_conversion_failed))
					if(target_status.favourited) {
						return TootApiResult(activity.getString(R.string.already_favourited))
					}
					
				} else {
					target_status = arg_status
				}
				
				if(access_info.isMisskey) {
					val params = access_info.putMisskeyApiToken(JSONObject())
						.put("noteId", target_status.id.toString())
					
					result = client.request(
						if(bSet) {
							"/api/notes/favorites/create"
						} else {
							"/api/notes/favorites/delete"
						}
						, params.toPostRequestBuilder()
					)
					
					// 正常レスポンスは 204 no content
					// 既にお気に入り済みならエラー文字列に'already favorited' が返る
					if(result?.response?.code() == 204
						|| result?.error?.contains("already favorited") == true
						|| result?.error?.contains("already not favorited") == true
					) {
						// 成功した
						target_status.favourited = bSet
						new_status = target_status
					}
					
				} else {
					val request_builder = Request.Builder().post(
						RequestBody.create(TootApiClient.MEDIA_TYPE_FORM_URL_ENCODED, "")
					)
					
					result = client.request(
						"/api/v1/statuses/${target_status.id}/" + if(bSet) "favourite" else "unfavourite"
						, request_builder
					)
					val jsonObject = result?.jsonObject
					new_status = TootParser(activity, access_info).status(jsonObject)
					
				}
				return result
				
			}
			
			override fun handleResult(result : TootApiResult?) {
				
				App1.getAppState(activity).resetBusyFav(access_info, arg_status)
				
				val new_status = this.new_status
				when {
					result == null -> {
					} // cancelled.
					new_status != null -> {
						
						val old_count = arg_status.favourites_count
						val new_count = new_status.favourites_count
						if(old_count != null && new_count != null) {
							if(access_info.isMisskey) {
								new_status.favourited = bSet
							}
							if(bSet && new_status.favourited && new_count <= old_count) {
								// 星をつけたのにカウントが上がらないのは違和感あるので、表示をいじる
								new_status.favourites_count = old_count + 1L
							} else if(! bSet && ! new_status.favourited && new_count >= old_count) {
								// 星を外したのにカウントが下がらないのは違和感あるので、表示をいじる
								// 0未満にはならない
								new_status.favourites_count =
									if(old_count < 1L) 0L else old_count - 1L
							}
						}
						
						for(column in App1.getAppState(activity).column_list) {
							column.findStatus(access_info.host, new_status.id) { account, status ->
								
								// 同タンス別アカウントでもカウントは変化する
								status.favourites_count = new_status.favourites_count
								
								// 同アカウントならfav状態を変化させる
								if(access_info.acct == account.acct) {
									status.favourited = new_status.favourited
								}
								
								true
							}
						}
						if(callback != null) callback()
						
					}
					
					else -> showToast(activity, true, result.error)
				}
				// 結果に関わらず、更新中状態から復帰させる
				activity.showColumnMatchAccount(access_info)
				
			}
		})
		
		// ファボ表示を更新中にする
		activity.showColumnMatchAccount(access_info)
	}
	
	fun boostFromAnotherAccount(
		activity : ActMain,
		timeline_account : SavedAccount,
		status : TootStatus?
	) {
		status ?: return
		
		val who_host = timeline_account.host
		val status_owner = timeline_account.getFullAcct(status.account)
		
		val isPrivateToot =
			! timeline_account.isMisskey && status.visibility == TootVisibility.PrivateFollowers
		if(isPrivateToot) {
			val list = ArrayList<SavedAccount>()
			for(a in SavedAccount.loadAccountList(activity)) {
				if(a.acct == status_owner) list.add(a)
			}
			if(list.isEmpty()) {
				showToast(activity, false, R.string.boost_private_toot_not_allowed)
				return
			}
			AccountPicker.pick(
				activity,
				bAllowPseudo = false,
				bAuto = false,
				message = activity.getString(R.string.account_picker_boost),
				accountListArg = list
			) { action_account ->
				boost(
					activity,
					action_account,
					status,
					status_owner,
					calcCrossAccountMode(timeline_account, action_account),
					activity.boost_complete_callback
				)
			}
		} else {
			AccountPicker.pick(
				activity,
				bAllowPseudo = false,
				bAuto = false,
				message = activity.getString(R.string.account_picker_boost),
				accountListArg = makeAccountListNonPseudo(activity, who_host)
			) { action_account ->
				boost(
					activity,
					action_account,
					status,
					status_owner,
					calcCrossAccountMode(timeline_account, action_account),
					activity.boost_complete_callback
				)
			}
		}
	}
	
	fun boost(
		activity : ActMain,
		access_info : SavedAccount,
		arg_status : TootStatus,
		status_owner_acct : String,
		nCrossAccountMode : Int,
		callback : EmptyCallback?,
		bSet : Boolean = true,
		bConfirmed : Boolean = false
	) {
		
		// アカウントからステータスにブースト操作を行っているなら、何もしない
		if(App1.getAppState(activity).isBusyBoost(access_info, arg_status)) {
			showToast(activity, false, R.string.wait_previous_operation)
			return
		}
		
		// 非公開トゥートをブーストできるのは本人だけ
		val isPrivateToot =
			! access_info.isMisskey && arg_status.visibility == TootVisibility.PrivateFollowers
		if(isPrivateToot && access_info.acct != status_owner_acct) {
			showToast(activity, false, R.string.boost_private_toot_not_allowed)
			return
		}
		
		// 必要なら確認を出す
		if(! bConfirmed) {
			DlgConfirm.open(
				activity,
				activity.getString(
					when {
						! bSet -> R.string.confirm_unboost_from
						isPrivateToot -> R.string.confirm_boost_private_from
						else -> R.string.confirm_boost_from
					},
					AcctColor.getNickname(access_info.acct)
				),
				object : DlgConfirm.Callback {
					override fun onOK() {
						boost(
							activity,
							access_info,
							arg_status,
							status_owner_acct,
							nCrossAccountMode,
							callback,
							bSet = bSet,
							bConfirmed = true
						)
					}
					
					override var isConfirmEnabled : Boolean
						get() = when(bSet) {
							true -> access_info.confirm_boost
							else -> access_info.confirm_unboost
						}
						set(value) {
							when(bSet) {
								true -> access_info.confirm_boost = value
								else -> access_info.confirm_unboost = value
							}
							access_info.saveSetting()
							activity.reloadAccountSetting(access_info)
						}
				})
			return
		}
		
		App1.getAppState(activity).setBusyBoost(access_info, arg_status)
		
		TootTaskRunner(activity, TootTaskRunner.PROGRESS_NONE).run(access_info, object : TootTask {
			
			var new_status : TootStatus? = null
			override fun background(client : TootApiClient) : TootApiResult? {
				
				val parser = TootParser(activity, access_info)
				
				var result : TootApiResult?
				
				val target_status : TootStatus
				if(nCrossAccountMode == CROSS_ACCOUNT_REMOTE_INSTANCE) {
					
					result = client.syncStatus(access_info,arg_status)
					if( result?.data == null) return result
					target_status = result.data as? TootStatus
						?: return TootApiResult(activity.getString(R.string.status_id_conversion_failed))
					if(target_status.reblogged) {
						return TootApiResult(activity.getString(R.string.already_boosted))
					}
				} else {
					// 既に自タンスのステータスがある
					target_status = arg_status
				}
				
				if(access_info.isMisskey) {
					if(! bSet) {
						
						return TootApiResult("Misskey has no 'unrenote' API.")
					} else {
						
						val params = access_info.putMisskeyApiToken(JSONObject())
							.put("renoteId", target_status.id.toString())
						
						result = client.request("/api/notes/create", params.toPostRequestBuilder())
						val jsonObject = result?.jsonObject
						if(jsonObject != null) {
							val new_status = parser.status(
								jsonObject.optJSONObject("createdNote") ?: jsonObject
							)
							// renoteそのものではなくrenoteされた元noteが欲しい
							this.new_status = new_status?.reblog ?: new_status
						}
						
						return result
					}
					
				} else {
					val request_builder = Request.Builder()
						.post(
							RequestBody.create(
								TootApiClient.MEDIA_TYPE_FORM_URL_ENCODED, ""
							)
						)
					
					result = client.request(
						"/api/v1/statuses/" + target_status.id + if(bSet) "/reblog" else "/unreblog",
						request_builder
					)
					val jsonObject = result?.jsonObject
					if(jsonObject != null) {
						
						val new_status = parser.status(jsonObject)
						
						// reblogはreblogを表すStatusを返す
						// unreblogはreblogしたStatusを返す
						this.new_status =
							if(new_status?.reblog != null) new_status.reblog else new_status
					}
					
					return result
					
				}
			}
			
			override fun handleResult(result : TootApiResult?) {
				App1.getAppState(activity).resetBusyBoost(access_info, arg_status)
				
				val new_status = this.new_status
				
				when {
					result == null -> {
					} // cancelled.
					new_status != null -> {
						// カウント数は遅延があるみたいなので、恣意的に表示を変更する
						// ブーストカウント数を加工する
						val old_count = arg_status.reblogs_count
						val new_count = new_status.reblogs_count
						if(old_count != null && new_count != null) {
							if(bSet && new_status.reblogged && new_count <= old_count) {
								// 星をつけたのにカウントが上がらないのは違和感あるので、表示をいじる
								new_status.reblogs_count = old_count + 1
							} else if(! bSet && ! new_status.reblogged && new_count >= old_count) {
								// 星を外したのにカウントが下がらないのは違和感あるので、表示をいじる
								// 0未満にはならない
								new_status.reblogs_count = if(old_count < 1) 0 else old_count - 1
							}
							
						}
						
						for(column in App1.getAppState(activity).column_list) {
							column.findStatus(access_info.host, new_status.id) { account, status ->
								
								// 同タンス別アカウントでもカウントは変化する
								status.reblogs_count = new_status.reblogs_count
								
								if(access_info.acct == account.acct) {
									// 同アカウントならreblog状態を変化させる
									status.reblogged = new_status.reblogged
								}
								true
							}
						}
						if(callback != null) callback()
					}
					
					else -> showToast(activity, true, result.error)
				}
				
				// 結果に関わらず、更新中状態から復帰させる
				activity.showColumnMatchAccount(access_info)
				
			}
		})
		
		// ブースト表示を更新中にする
		activity.showColumnMatchAccount(access_info)
	}
	
	fun delete(
		activity : ActMain, access_info : SavedAccount, status_id : EntityId
	) {
		
		TootTaskRunner(activity).run(access_info, object : TootTask {
			override fun background(client : TootApiClient) : TootApiResult? {
				val request_builder = Request.Builder().delete()
				
				return client.request("/api/v1/statuses/$status_id", request_builder)
			}
			
			override fun handleResult(result : TootApiResult?) {
				if(result == null) return  // cancelled.
				
				if(result.jsonObject != null) {
					showToast(activity, false, R.string.delete_succeeded)
					for(column in App1.getAppState(activity).column_list) {
						column.onStatusRemoved(access_info.host, status_id)
					}
				} else {
					showToast(activity, false, result.error)
				}
				
			}
		})
	}
	
	/////////////////////////////////////////////////////////////////////////////////////
	// open conversation
	
	// ローカルかリモートか判断する
	fun conversation(
		activity : ActMain, pos : Int, access_info : SavedAccount, status : TootStatus
	) {
		if(access_info.isNA || ! access_info.host.equals(status.host_access, ignoreCase = true)) {
			conversationOtherInstance(activity, pos, status)
		} else {
			conversationLocal(activity, pos, access_info, status.id)
		}
	}
	
	// ローカルから見える会話の流れを表示する
	fun conversationLocal(
		activity : ActMain,
		pos : Int,
		access_info : SavedAccount,
		status_id : EntityId
	) {
		activity.addColumn(pos, access_info, Column.TYPE_CONVERSATION, status_id)
	}
	
	// リモートかもしれない会話の流れを表示する
	fun conversationOtherInstance(
		activity : ActMain, pos : Int, status : TootStatus?
	) {
		if(status == null) return
		val url = status.url
		
		if(url == null || url.isEmpty()) {
			// URLが不明なトゥートというのはreblogの外側のアレ
			return
		}
		
		when {
			
			// 検索サービスではステータスTLをどのタンスから読んだのか分からない
			status.host_access == null ->
				conversationOtherInstance(
					activity
					, pos
					, url
					, TootStatus.validStatusId(status.id)
						?: TootStatus.findStatusIdFromUri(
							status.uri,
							status.url,
							bAllowStringId = true
						)
				)
			
			// TLアカウントのホストとトゥートのアカウントのホストが同じ
			status.host_original == status.host_access ->
				conversationOtherInstance(
					activity
					, pos
					, url
					, TootStatus.validStatusId(status.id)
						?: TootStatus.findStatusIdFromUri(
							status.uri,
							status.url,
							bAllowStringId = true
						)
				)
			
			else -> {
				// トゥートを取得したタンスと投稿元タンスが異なる場合
				// status.id はトゥートを取得したタンスでのIDである
				// 投稿元タンスでのIDはuriやURLから調べる
				// pleromaではIDがuuidなので失敗する(その時はURLを検索してIDを見つける)
				conversationOtherInstance(
					activity
					, pos
					, url
					, TootStatus.findStatusIdFromUri(
						status.uri,
						status.url,
						bAllowStringId = true
					)
					, status.host_access
					, TootStatus.validStatusId(status.id)
				)
			}
		}
	}
	
	// アプリ外部からURLを渡された場合に呼ばれる
	fun conversationOtherInstance(
		activity : ActMain,
		pos : Int,
		url : String,
		status_id_original : EntityId? = null,
		host_access : String? = null,
		status_id_access : EntityId? = null
	) {
		
		val dialog = ActionsDialog()
		
		val host_original = Uri.parse(url).authority
		
		// 選択肢：ブラウザで表示する
		dialog.addAction(
			activity.getString(
				R.string.open_web_on_host,
				host_original
			)
		) { App1.openCustomTab(activity, url) }
		
		// トゥートの投稿元タンスにあるアカウント
		val local_account_list = ArrayList<SavedAccount>()
		
		// TLを読んだタンスにあるアカウント
		val access_account_list = ArrayList<SavedAccount>()
		
		// その他のタンスにあるアカウント
		val other_account_list = ArrayList<SavedAccount>()
		
		for(a in SavedAccount.loadAccountList(activity)) {
			
			// 疑似アカウントは後でまとめて処理する
			if(a.isPseudo) continue
			
			if(status_id_original != null && a.host.equals(host_original, ignoreCase = true)) {
				// アクセス情報＋ステータスID でアクセスできるなら
				// 同タンスのアカウントならステータスIDの変換なしに表示できる
				local_account_list.add(a)
			} else if(status_id_access != null && a.host.equals(host_access, ignoreCase = true)) {
				// 既に変換済みのステータスIDがあるなら、そのアカウントでもステータスIDの変換は必要ない
				access_account_list.add(a)
			} else {
				// 別タンスでも実アカウントなら検索APIでステータスIDを変換できる
				other_account_list.add(a)
			}
		}
		
		// 同タンスのアカウントがないなら、疑似アカウントで開く選択肢
		if(local_account_list.isEmpty()) {
			if(status_id_original != null) {
				dialog.addAction(
					activity.getString(R.string.open_in_pseudo_account, "?@$host_original")
				) {
					val sa = addPseudoAccount(activity, host_original)
					if(sa != null) {
						conversationLocal(activity, pos, sa, status_id_original)
					}
				}
			} else {
				dialog.addAction(
					activity.getString(R.string.open_in_pseudo_account, "?@$host_original")
				) {
					val sa = addPseudoAccount(activity, host_original)
					if(sa != null) {
						conversationRemote(activity, pos, sa, url)
					}
				}
			}
		}
		
		// ローカルアカウント
		if(status_id_original != null) {
			SavedAccount.sort(local_account_list)
			for(a in local_account_list) {
				dialog.addAction(
					AcctColor.getStringWithNickname(
						activity,
						R.string.open_in_account,
						a.acct
					)
				) { conversationLocal(activity, pos, a, status_id_original) }
			}
		}
		
		// アクセスしたアカウント
		if(status_id_access != null) {
			SavedAccount.sort(access_account_list)
			for(a in access_account_list) {
				dialog.addAction(
					AcctColor.getStringWithNickname(
						activity,
						R.string.open_in_account,
						a.acct
					)
				) { conversationLocal(activity, pos, a, status_id_access) }
			}
		}
		
		// その他の実アカウント
		SavedAccount.sort(other_account_list)
		for(a in other_account_list) {
			dialog.addAction(
				AcctColor.getStringWithNickname(
					activity,
					R.string.open_in_account,
					a.acct
				)
			) { conversationRemote(activity, pos, a, url) }
		}
		
		dialog.show(activity, activity.getString(R.string.open_status_from))
	}
	
	private fun conversationRemote(
		activity : ActMain, pos : Int, access_info : SavedAccount, remote_status_url : String
	) {
		TootTaskRunner(activity)
			.progressPrefix(activity.getString(R.string.progress_synchronize_toot))
			.run(access_info, object : TootTask {
				
				var local_status_id : EntityId? = null
				override fun background(client : TootApiClient) : TootApiResult? {
					var result : TootApiResult?
					if(access_info.isPseudo) {
						// 疑似アカウントではURLからIDを取得するのにHTMLと正規表現を使う
						result = client.getHttp(remote_status_url)
						val string = result?.string
						if(string != null) {
							try {
								val m = reDetailedStatusTime.matcher(string)
								if(m.find()) {
									local_status_id = EntityIdLong(m.group(1).toLong(10))
								}
							} catch(ex : Throwable) {
								log.e(ex, "openStatusRemote: can't parse status id from HTML data.")
							}
							
							if(local_status_id == null) {
								result = TootApiResult(
									activity.getString(R.string.status_id_conversion_failed)
								)
							}
						}
					} else {
						result = client.syncStatus(access_info,remote_status_url)
						if( result?.data == null ) return result
						val status = result.data as? TootStatus
							?: return TootApiResult(activity.getString(R.string.status_id_conversion_failed))
						local_status_id = status.id
						log.d("status id conversion %s => %s", remote_status_url, status.id)
					}
					return result
				}
				
				override fun handleResult(result : TootApiResult?) {
					if(result == null) return // cancelled.
					
					val local_status_id = this.local_status_id
					if(local_status_id != null) {
						conversationLocal(activity, pos, access_info, local_status_id)
					} else {
						showToast(activity, true, result.error)
					}
				}
			})
		
	}
	
	////////////////////////////////////////
	// profile pin
	
	fun pin(
		activity : ActMain, access_info : SavedAccount, status : TootStatus, bSet : Boolean
	) {
		
		TootTaskRunner(activity)
			.progressPrefix(activity.getString(R.string.profile_pin_progress))
			
			.run(access_info, object : TootTask {
				
				var new_status : TootStatus? = null
				override fun background(client : TootApiClient) : TootApiResult? {
					val result : TootApiResult?
					
					val request_builder = Request.Builder()
						.post(
							RequestBody.create(
								TootApiClient.MEDIA_TYPE_FORM_URL_ENCODED, ""
							)
						)
					
					result = client.request(
						if(bSet)
							"/api/v1/statuses/" + status.id + "/pin"
						else
							"/api/v1/statuses/" + status.id + "/unpin", request_builder
					)
					
					new_status = TootParser(activity, access_info).status(result?.jsonObject)
					
					return result
				}
				
				override fun handleResult(result : TootApiResult?) {
					
					val new_status = this.new_status
					
					when {
						result == null -> {
							// cancelled.
						}
						
						new_status != null -> {
							for(column in App1.getAppState(activity).column_list) {
								if(access_info.acct == column.access_info.acct) {
									column.findStatus(
										access_info.host,
										new_status.id
									) { _, status ->
										status.pinned = bSet
										true
									}
								}
							}
						}
						
						else -> showToast(activity, true, result.error)
					}
					
					// 結果に関わらず、更新中状態から復帰させる
					activity.showColumnMatchAccount(access_info)
					
				}
			})
		
	}
	
	/////////////////////////////////////////////////////////////////////////////////
	// reply
	
	fun reply(
		activity : ActMain, access_info : SavedAccount, status : TootStatus
	) {
		ActPost.open(
			activity,
			ActMain.REQUEST_CODE_POST,
			access_info.db_id,
			reply_status = status
		)
	}
	
	fun replyFromAnotherAccount(
		activity : ActMain, timeline_account : SavedAccount, status : TootStatus?
	) {
		if(status == null) return
		val who_host = timeline_account.host
		AccountPicker.pick(
			activity,
			bAllowPseudo = false,
			bAuto = false,
			message = activity.getString(R.string.account_picker_reply),
			accountListArg = makeAccountListNonPseudo(activity, who_host)
		) { ai ->
			if(ai.host.equals(status.host_access, ignoreCase = true)) {
				// アクセス元ホストが同じならステータスIDを使って返信できる
				reply(activity, ai, status)
			} else {
				// それ以外の場合、ステータスのURLを検索APIに投げることで返信できる
				replyRemote(activity, ai, status.url)
			}
		}
	}
	
	private fun replyRemote(
		activity : ActMain, access_info : SavedAccount, remote_status_url : String?
	) {
		if(remote_status_url == null || remote_status_url.isEmpty()) return
		
		TootTaskRunner(activity)
			.progressPrefix(activity.getString(R.string.progress_synchronize_toot))
			
			.run(access_info, object : TootTask {
				
				var local_status : TootStatus? = null
				override fun background(client : TootApiClient) : TootApiResult? {
					val result = client.syncStatus(access_info,remote_status_url)
					if( result?.data == null) return result
					local_status = result.data as? TootStatus
						?: return TootApiResult(activity.getString(R.string.status_id_conversion_failed))
					return result
				}
				
				override fun handleResult(result : TootApiResult?) {
					
					result ?: return // cancelled.
					
					val ls = local_status
					if(ls != null) {
						reply(activity, access_info, ls)
					} else {
						showToast(activity, true, result.error)
					}
				}
			})
	}
	
	// 投稿画面を開く。初期テキストを指定する
	fun redraft(
		activity : ActMain,
		accessInfo : SavedAccount,
		status : TootStatus
	) {
		activity.post_helper.closeAcctPopup()
		
		if( accessInfo.isMisskey){
			ActPost.open(activity, ActMain.REQUEST_CODE_POST, accessInfo.db_id, redraft_status = status, reply_status = status.reply)
			return
		}
		
		if(status.in_reply_to_id == null) {
			ActPost.open(activity, ActMain.REQUEST_CODE_POST, accessInfo.db_id, redraft_status = status)
			return
		}
		
		TootTaskRunner(activity).run(accessInfo, object : TootTask {
			
			var reply_status : TootStatus? = null
			override fun background(client : TootApiClient) : TootApiResult? {
				val result = client.request("/api/v1/statuses/${status.in_reply_to_id}")
				reply_status = TootParser(activity, accessInfo).status(result?.jsonObject)
				return result
			}
			
			override fun handleResult(result : TootApiResult?) {
				if(result == null) return  // cancelled.
				
				val reply_status = this.reply_status
				if(reply_status != null) {
					ActPost.open(
						activity,
						ActMain.REQUEST_CODE_POST,
						accessInfo.db_id,
						redraft_status = status,
						reply_status = reply_status
					)
					return
				}
				val error = result.error ?: "(no information)"
				showToast(activity, true, activity.getString(R.string.cant_sync_toot) + " : $error")
			}
		})
	}
	////////////////////////////////////////
	
	fun muteConversation(
		activity : ActMain, access_info : SavedAccount, status : TootStatus
	) {
		// toggle change
		val bMute = ! status.muted
		
		TootTaskRunner(activity).run(access_info, object : TootTask {
			
			var local_status : TootStatus? = null
			
			override fun background(client : TootApiClient) : TootApiResult? {
				val request_builder = Request.Builder()
					.post(RequestBody.create(TootApiClient.MEDIA_TYPE_FORM_URL_ENCODED, ""))
				
				val result = client.request(
					"/api/v1/statuses/" + status.id + if(bMute) "/mute" else "/unmute",
					request_builder
				)
				
				local_status = TootParser(activity, access_info).status(result?.jsonObject)
				
				return result
			}
			
			override fun handleResult(result : TootApiResult?) {
				result ?: return // cancelled.
				
				val ls = local_status
				if(ls != null) {
					for(column in App1.getAppState(activity).column_list) {
						if(access_info.acct == column.access_info.acct) {
							column.findStatus(access_info.host, ls.id) { _, status ->
								status.muted = bMute
								true
							}
						}
					}
					showToast(
						activity,
						true,
						if(bMute) R.string.mute_succeeded else R.string.unmute_succeeded
					)
				} else {
					showToast(activity, true, result.error)
				}
			}
		})
	}
	
}
