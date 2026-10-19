package io.github.wayneshao.rayneo.ime;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.util.UUID;
import org.json.JSONObject;

/** Opt-in WebView editor adapter. Its original WebViewClient, navigation and cookies stay intact. */
public class EmbeddedWebView extends WebView {
    private final EmbeddedInput.Host host;
    private final String bridgeName = "EmbeddedInput_" + UUID.randomUUID().toString().replace("-", "");
    private String document = "";
    private WebEditor editor;
    private boolean destroyed;
    public EmbeddedWebView(Context context, EmbeddedInput.Host host) {
        super(context); this.host = host; addJavascriptInterface(new FocusBridge(), bridgeName);
    }
    @Override public boolean onCheckIsTextEditor() { return false; }
    public static void pageFinished(WebView view) { if (view instanceof EmbeddedWebView) ((EmbeddedWebView) view).installPage(); }
    private void installPage() {
        if (destroyed) return;
        if (editor != null) { EmbeddedInput.close(this); editor.alive = false; }
        document = UUID.randomUUID().toString();
        final String token = JSONObject.quote(document), bridge = JSONObject.quote(bridgeName);
        String js = "(function(){if(window.__embeddedInput)window.__embeddedInput.stop();"
                + "var token=" + token + ",bridge=window[" + bridge + "],target=null,range=null,serial=0;"
                + "function eligible(e){return e&&((/^(INPUT|TEXTAREA)$/.test(e.tagName)&&!e.disabled&&!e.readOnly&&!/^(button|submit|reset|checkbox|radio|file|hidden|range|color|date|time|month|week|datetime-local)$/.test(e.type))||e.isContentEditable);}"
                + "function quiet(e){if(eligible(e))e.setAttribute('inputmode','none');}"
                + "function data(){if(!target||!target.isConnected)return null;var password=target.type==='password';return {token:token,id:serial,password:password,ascii:password||/^(email|url|tel|number)$/.test(target.type),text:password?'••••••':(target.value===undefined?target.innerText:target.value)};}"
                + "function focus(e){var node=e.target;if(!eligible(node))return;quiet(node);if(target!==node){target=node;serial++;}"
                + "var s=window.getSelection();range=node.isContentEditable&&s.rangeCount?s.getRangeAt(0).cloneRange():null;bridge.open(JSON.stringify(data()));}"
                + "function mutate(id,op,value){if(id!==serial||!target||!target.isConnected)return null;"
                + "if(op==='submit'){var node=target,result=data(),form=node.form,submitted=false;function observed(){submitted=true;}if(form)form.addEventListener('submit',observed,true);try{node.dispatchEvent(new Event('change',{bubbles:true}));var allowed=node.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));allowed=node.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}))&&allowed;if(allowed&&!submitted&&form&&node.form===form&&node.isConnected&&node.tagName!=='TEXTAREA'&&form.requestSubmit)form.requestSubmit();node.blur();return result;}finally{if(form)form.removeEventListener('submit',observed,true);}}"
                + "if(op==='clear'&&target.isContentEditable){var s=window.getSelection(),r=document.createRange();r.selectNodeContents(target);s.removeAllRanges();s.addRange(r);document.execCommand('delete',false,null);if(s.rangeCount)range=s.getRangeAt(0).cloneRange();return data();}"
                + "if(target.isContentEditable){var s=window.getSelection();if(range){s.removeAllRanges();s.addRange(range);}"
                + "if(op==='insert')document.execCommand('insertText',false,value);else if(op==='delete')document.execCommand('delete',false,null);"
                + "else s.modify('move',op==='left'?'backward':'forward','character');if(s.rangeCount)range=s.getRangeAt(0).cloneRange();return data();}"
                + "var old=target.value,a=target.selectionStart,b=target.selectionEnd;if(a===null){a=old.length;b=a;}"
                + "if(op==='clear'){a=0;b=old.length;}"
                + "if(op==='left'||op==='right'){var p=b;if(op==='left'&&p>0)p-=p>1&&/[\\uDC00-\\uDFFF]/.test(old[p-1])?2:1;"
                + "if(op==='right'&&p<old.length)p+=old.codePointAt(p)>65535?2:1;try{target.setSelectionRange(p,p);}catch(e){}return data();}"
                + "if(op==='delete'&&a===b&&a>0)a-=a>1&&/[\\uDC00-\\uDFFF]/.test(old[a-1])?2:1;"
                + "var insert=op==='insert'?value:'';if(target.maxLength>=0)insert=insert.slice(0,Math.max(0,target.maxLength-(old.length-(b-a))));"
                + "var proto=target.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "Object.getOwnPropertyDescriptor(proto,'value').set.call(target,old.slice(0,a)+insert+old.slice(b));"
                + "try{target.setSelectionRange(a+insert.length,a+insert.length);}catch(e){}"
                + "target.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:op==='delete'||op==='clear'?'deleteContentBackward':'insertText',data:insert}));return data();}"
                + "document.querySelectorAll('input,textarea,[contenteditable]').forEach(quiet);"
                + "var observer=new MutationObserver(function(){document.querySelectorAll('input,textarea,[contenteditable]').forEach(function(e){if(e.getAttribute('inputmode')!=='none')quiet(e);});});"
                + "observer.observe(document.documentElement,{childList:true,subtree:true});"
                + "document.addEventListener('focusin',focus,true);document.addEventListener('click',focus,true);"
                + "window.__embeddedInput={token:token,mutate:mutate,stop:function(){observer.disconnect();document.removeEventListener('focusin',focus,true);document.removeEventListener('click',focus,true);}};})()";
        evaluateJavascript(js, null);
    }
    private final class FocusBridge {
        @JavascriptInterface public void open(String value) {
            post(() -> {
                if (destroyed || !isAttachedToWindow() || !hasWindowFocus()) return;
                try {
                    JSONObject state = new JSONObject(value);
                    if (!document.equals(state.optString("token"))) return;
                    int id = state.getInt("id");
                    if (editor == null || editor.id != id || !editor.token.equals(document)) {
                        if (editor != null) { editor.alive = false; EmbeddedInput.close(editor); }
                        editor = new WebEditor(state);
                    }
                    else editor.update(state);
                    EmbeddedInput.show(editor, host);
                } catch (Exception ignored) { }
            });
        }
    }
    private final class WebEditor implements EditorTarget {
        final int id; final String token; final boolean secret, ascii;
        String value = ""; boolean alive = true; Runnable changed;
        WebEditor(JSONObject state) { id = state.optInt("id"); token = document; secret = state.optBoolean("password"); ascii = state.optBoolean("ascii"); update(state); }
        void update(JSONObject state) { value = state.optString("text"); if (changed != null) changed.run(); }
        public View owner() { return EmbeddedWebView.this; }
        public Object identity() { return this; }
        public boolean valid() { return editor == this && !destroyed && alive && token.equals(document) && isAttachedToWindow() && isShown(); }
        public boolean password() { return secret; }
        public boolean preferAscii() { return ascii; }
        public String preview() { return value; }
        public void observe(Runnable callback) { changed = callback; }
        public void commit(String text) { edit("insert", text); }
        public void delete() { edit("delete", ""); }
        public void clear() { edit("clear", ""); }
        public void move(int direction) { edit(direction < 0 ? "left" : "right", ""); }
        public void submit() { edit("submit", ""); }
        void edit(String operation, String text) {
            if (!valid()) return;
            evaluateJavascript("(function(){var k=window.__embeddedInput;return k&&k.token===" + JSONObject.quote(token)
                    + "?k.mutate(" + id + "," + JSONObject.quote(operation) + "," + JSONObject.quote(text) + "):null;})()", result -> {
                if (!valid()) return;
                try { if (result == null || result.equals("null")) { alive = false; EmbeddedInput.close(this); } else update(new JSONObject(result)); }
                catch (Exception ignored) { alive = false; EmbeddedInput.close(this); }
            });
        }
    }
    @Override protected void onDetachedFromWindow() { EmbeddedInput.close(this); if (editor != null) editor.alive = false; super.onDetachedFromWindow(); }
    @Override public void destroy() { destroyed = true; EmbeddedInput.close(this); removeJavascriptInterface(bridgeName); super.destroy(); }
}
