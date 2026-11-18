/**
 * 手机扫码后的配置页脚本。
 * 只做三件事：回显当前配置、提交点播/直播两组地址+名字、轻提示。
 * 不依赖 jquery（页面已不再引入），老手机浏览器也能跑。
 */
(function () {
	'use strict';

	function $(id) {
		return document.getElementById(id);
	}

	var toastTimer = null;

	function toast(msg, kind) {
		var el = $('toast');
		if (!el) return;
		el.textContent = msg;
		el.className = 'toast show' + (kind ? ' ' + kind : '');
		if (toastTimer) clearTimeout(toastTimer);
		toastTimer = setTimeout(function () {
			el.className = 'toast';
		}, 2800);
	}

	function request(method, url, body, cb) {
		var xhr = new XMLHttpRequest();
		xhr.open(method, url, true);
		xhr.timeout = 12000;
		if (body) xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded;charset=UTF-8');
		xhr.onreadystatechange = function () {
			if (xhr.readyState !== 4) return;
			if (xhr.status >= 200 && xhr.status < 300) cb(null, xhr.responseText);
			else cb(new Error('HTTP ' + xhr.status), null);
		};
		xhr.ontimeout = function () {
			cb(new Error('timeout'), null);
		};
		xhr.onerror = function () {
			cb(new Error('network'), null);
		};
		xhr.send(body || null);
	}

	function setBadge(id, url) {
		var el = $(id);
		if (!el) return;
		if (url) {
			el.textContent = '已配置';
			el.className = 'badge on';
		} else {
			el.textContent = '未配置';
			el.className = 'badge';
		}
	}

	/* 打开页面先把电视上现在的配置填进来，用户一眼知道在用哪个源 */
	function loadState() {
		request('GET', '/state', null, function (err, text) {
			if (err) return;
			var d;
			try {
				d = JSON.parse(text);
			} catch (e) {
				return;
			}
			$('vodUrl').value = d.vodUrl || '';
			$('vodName').value = d.vodName || '';
			$('liveUrl').value = d.liveUrl || '';
			$('liveName').value = d.liveName || '';
			setBadge('vodBadge', d.vodUrl);
			setBadge('liveBadge', d.liveUrl);
		});
	}

	function save(kind) {
		var isVod = kind === 'api';
		var urlEl = $(isVod ? 'vodUrl' : 'liveUrl');
		var nameEl = $(isVod ? 'vodName' : 'liveName');
		var btn = $(isVod ? 'vodSave' : 'liveSave');
		var url = (urlEl.value || '').trim();
		var name = (nameEl.value || '').trim();

		if (isVod && !url) {
			toast('点播接口地址不能为空', 'err');
			urlEl.focus();
			return;
		}

		var label = btn.textContent;
		btn.disabled = true;
		btn.textContent = '保存中…';

		var body = 'do=' + kind +
			'&url=' + encodeURIComponent(url) +
			'&name=' + encodeURIComponent(name);

		request('POST', '/action', body, function (err) {
			btn.disabled = false;
			btn.textContent = label;
			if (err) {
				toast('保存失败：确认手机和电视连的是同一个 WiFi', 'err');
				return;
			}
			setBadge(isVod ? 'vodBadge' : 'liveBadge', url);
			toast(isVod ? '点播接口已保存，电视正在重启' : '直播接口已保存，电视正在重启', 'ok');
		});
	}

	function bind() {
		$('vodSave').onclick = function () {
			save('api');
		};
		$('liveSave').onclick = function () {
			save('live');
		};
		loadState();
	}

	if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bind);
	else bind();
})();
