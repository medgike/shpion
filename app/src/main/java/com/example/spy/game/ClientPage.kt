package com.example.spy.game

object ClientPage {

    val html: String = """
<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no"/>
<title>Spy</title>
<style>
  :root{--bg:#0f1220;--card:#1a1f35;--card2:#232a47;--txt:#eef1ff;--muted:#9aa3c7;--accent:#ff5470;--accent2:#5b8cff;--ok:#39d98a}
  *{box-sizing:border-box}
  body{margin:0;font-family:-apple-system,BlinkMacSystemFont,sans-serif;background:var(--bg);color:var(--txt);min-height:100vh;display:flex;flex-direction:column}
  .wrap{max-width:520px;margin:0 auto;padding:20px 16px 40px;width:100%;flex:1;display:flex;flex-direction:column}
  h1{font-size:22px;margin:4px 0 2px}
  .sub{color:var(--muted);font-size:13px;margin-bottom:18px}
  .card{background:var(--card);border-radius:18px;padding:20px;margin-bottom:14px;box-shadow:0 10px 30px rgba(0,0,0,.35)}
  .word{font-size:40px;font-weight:800;text-align:center;margin:10px 0}
  .cat{text-align:center;color:var(--accent2);font-size:14px;text-transform:uppercase;letter-spacing:2px}
  .hint{color:var(--muted);font-size:13px;text-align:center;margin-top:12px;line-height:1.5}
  input,button{font-size:17px;border-radius:14px;border:none;outline:none}
  input{width:100%;padding:14px 16px;background:var(--card2);color:var(--txt);margin-bottom:12px}
  button{width:100%;padding:15px;background:var(--accent);color:#fff;font-weight:700;cursor:pointer}
  button.secondary{background:var(--card2);color:var(--txt)}
  .player{display:flex;align-items:center;justify-content:space-between;padding:12px 14px;background:var(--card2);border-radius:12px;margin-bottom:8px}
  .player .dot{width:9px;height:9px;border-radius:50%;background:var(--ok);margin-right:10px;display:inline-block}
  .player .dot.off{background:#5a5f7a}
  .pname{display:flex;align-items:center;font-weight:600}
  .badge{font-size:11px;color:var(--muted);background:#00000030;padding:3px 8px;border-radius:8px}
  .vote-btn{background:var(--card2);color:var(--txt);text-align:left;margin-bottom:8px}
  .vote-btn.sel{background:var(--accent);color:#fff}
  .mrx-opt.sel{background:var(--accent2)!important;color:#fff!important}
  .status{text-align:center;color:var(--muted);font-size:14px;margin:8px 0}
  .big{font-size:20px;font-weight:700;text-align:center;margin:6px 0}
  .timer{font-size:15px;color:var(--accent2);text-align:center;margin-bottom:10px}
  .result{font-size:18px;font-weight:700;text-align:center;line-height:1.5;margin:10px 0}
  .rules{font-size:13px;color:var(--muted);line-height:1.6;white-space:pre-line}
  .hidden{display:none}
</style>
</head>
<body>
<div class="wrap">
  <h1>Spy Game</h1>
  <div class="sub" id="conn">...</div>
  <div id="app"></div>
</div>
<script>
"use strict";
(function(){
  var app=document.getElementById("app"),connEl=document.getElementById("conn");
  var ws=null,me=null,phase="LOBBY",selectedSuspect=null;
  var kicked=false,myWord=null,myCategory=null,myGuessOptions=[],myGuess=null,myIsMrX=false;
  var lobbySpyCount=1,lobbyHasMrX=false;
  // Токен переподключения переживает обновление страницы (частый случай: свайп вниз
  // в мобильном браузере). sessionStorage, а не localStorage — он per-tab, поэтому
  // два игрока с одного телефона в разных вкладках не затирают друг друга.
  var token=null,savedName="";
  try{token=sessionStorage.getItem("spyToken");savedName=sessionStorage.getItem("spyName")||"";}catch(e){}
  function saveSession(){try{if(token)sessionStorage.setItem("spyToken",token);if(savedName)sessionStorage.setItem("spyName",savedName);}catch(e){}}
  function dropSession(){try{sessionStorage.removeItem("spyToken");}catch(e){}}
  function connect(){
    var proto=location.protocol==="https:"?"wss":"ws";
    ws=new WebSocket(proto+"://"+location.host+"/ws");
    // Имя шлём только при первом входе с сохранённым токеном (после обновления
    // страницы): если сервер перезапустился и токен ему неизвестен, игрок войдёт
    // под своим именем, а не как безликий «Игрок».
    ws.onopen=function(){connEl.textContent="На связи";if(token){send({type:"Join",name:me?"":savedName,token:token});}else{renderJoin();}};
    ws.onclose=function(){if(kicked)return;connEl.textContent="Переподключаюсь...";setTimeout(connect,1500);};
    ws.onmessage=function(ev){handle(JSON.parse(ev.data));};
  }
  function send(obj){if(ws&&ws.readyState===1)ws.send(JSON.stringify(obj));}
  function handle(msg){
    var t=msg.type;
    if(t==="Joined"){me=msg.playerId;token=msg.token;saveSession();return;}
    if(t==="Lobby"){phase="LOBBY";myWord=null;myCategory=null;myGuessOptions=[];myGuess=null;myIsMrX=false;lobbySpyCount=msg.spyCount||0;lobbyHasMrX=!!msg.hasMrX;renderLobby(msg);return;}
    if(t==="RoleAssignment"){phase="REVEAL";myWord=msg.word;myCategory=msg.category;myGuessOptions=msg.guessOptions||[];myGuess=null;myIsMrX=!!msg.isMrX;renderRole(msg);return;}
    if(t==="Discussion"){phase="DISCUSSION";renderDiscussion(msg);return;}
    if(t==="VotingStarted"){phase="VOTING";selectedSuspect=null;renderVoting(msg);return;}
    if(t==="VoteProgress"){upVP(msg);return;}
    if(t==="FinalGuess"){phase="FINAL";renderFinalGuess(msg);return;}
    if(t==="RoundResult"){phase="RESULT";renderResult(msg);return;}
    if(t==="Notice"){toast(msg.text,msg.isError);return;}
    if(t==="Kicked"){kicked=true;dropSession();app.innerHTML='<div class="card"><div class="big">Вас исключили</div><div class="hint">'+esc(msg.reason)+'</div></div>';connEl.textContent="";return;}
  }
  function esc(s){return(s||"").replace(/[&<>"]/g,function(c){return{"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c];});}
  function toast(t,e){connEl.textContent=t;connEl.style.color=e?"#ff5470":"#9aa3c7";}
  function fmt(s){s=Math.max(0,s|0);var m=(s/60)|0,ss=s%60;return m+":"+(ss<10?"0":"")+ss;}
  function getRulesText(){
    var sc=lobbySpyCount,mx=lobbyHasMrX;
    var t="Каждому тайно выдаётся слово (или роль). По кругу все называют ПО ОДНОЙ ассоциации к своему слову. Порядок хода перемешивается каждый раунд. Совет: два круга ассоциаций — и голосуйте.\n\n";
    t+="Голосование: каждый отдаёт один голос за лишнего. Обвиняется тот, у кого голосов строго больше всех. При равенстве — ПЕРЕГОЛОСОВАНИЕ между лидерами (один раз). Если и оно вничью — никого не обвинили.\n\n";
    t+="У обвинённого есть «ПОСЛЕДНЕЕ СЛОВО» (30 сек): лишний может угадать слово мирных и спастись до ничьей, мирный — просто пропускает.\n\n";
    if(sc>0&&!mx){
      t+="РЕЖИМ: Шпион\n\n";
      t+="Большинство получает одно слово. Шпион — ПОХОЖЕЕ, но другое из той же категории (напр. «Чай» вместо «Кофе»). Шпион сам не знает наверняка, что он шпион.\n\n";
      t+="Мирные: вычислить, кто описывает «не то» слово.\n";
      t+="Шпион: маскироваться. Если поймают — можно один раз попробовать назвать слово мирных.\n\n";
      t+="ИСХОДЫ:\n";
      t+="• Поймали шпиона, слово НЕ угадал → Мирные победили.\n";
      t+="• Поймали шпиона, но он УГАДАЛ слово (из вариантов) → Ничья.\n";
      t+="• Обвинили мирного или дважды разделились голоса → Шпион победил.\n";
      t+="• Шпиона не нашли и он угадал слово → Шпион победил вдвойне.\n\n";
      t+="Если шпионов двое: у обоих одинаковое слово, друг друга они не знают. Обвинили любого из них → мирные победили.";
    } else if(sc===0&&mx){
      t+="РЕЖИМ: Mr. X\n\n";
      t+="Все получают одинаковое слово, кроме Mr. X. Он не знает НИЧЕГО, кроме категории: слова у него нет вообще. Ему приходится вычислять слово по чужим ассоциациям и одновременно выдавать свои, чтобы не спалиться.\n\n";
      t+="Мирные: вычислить того, кто не знает слова.\n";
      t+="Mr. X: не выдать себя. Если поймают — выбрать слово из 6 вариантов.\n\n";
      t+="ИСХОДЫ:\n";
      t+="• Поймали Mr. X, слово НЕ угадал → Мирные победили.\n";
      t+="• Поймали Mr. X, но он УГАДАЛ слово → Ничья.\n";
      t+="• Обвинили мирного или дважды разделились голоса → Mr. X победил.";
    } else if(sc>0&&mx){
      t+="РЕЖИМ: Шпион + Mr. X\n\n";
      t+="Большинство — одно слово. Шпион(ы) — похожее другое. Mr. X — вообще без слова, только категория. Лишних несколько, но за раунд обвиняют одного. Mr. X подключается только если мирных всё равно остаётся больше, чем лишних.\n\n";
      t+="Мирные: найти любого лишнего.\n";
      t+="Шпион: маскироваться; пойман — угадать слово мирных.\n";
      t+="Mr. X: блефовать; пойман — выбрать из 6 вариантов.\n\n";
      t+="ИСХОДЫ:\n";
      t+="• Поймали лишнего (шпиона или Mr. X), слово НЕ угадал → Мирные победили.\n";
      t+="• Поймали лишнего, и он УГАДАЛ слово → Ничья.\n";
      t+="• Обвинили мирного или дважды разделились голоса → Лишние победили.";
    }
    t+="\n\nОЧКИ (копятся всю партию). Платят за твою собственную дедукцию, а не за удачу:\n";
    t+="• Твой голос попал в лишнего → +2 лично (даже если общий голос не попал).\n";
    t+="• Лишнего в итоге поймали → ещё +1 каждому мирному.\n";
    t+="• Шпион не пойман → +4. Mr. X не пойман → +5 (роль сложнее).\n";
    t+="• Непойманный лишний угадал слово → ещё +1.\n";
    t+="• Пойманный лишний угадал слово → +2 ему (ничья).\n";
    t+="• Лишний уцелел, но спалился напарник → половина награды.";
    return t;
  }
  function renderJoin(){
    app.innerHTML='<div class="card"><input id="name" placeholder="Ваше имя" maxlength="16" autocomplete="off" value="'+esc(savedName)+'"/><button id="join">Войти в игру</button></div>';
    document.getElementById("join").onclick=function(){var n=document.getElementById("name").value.trim();if(!n){toast("Введите имя",true);return;}savedName=n;send({type:"Join",name:n,token:token});};
  }
  function renderLobby(msg){
    if(!me){renderJoin();return;}
    var list=msg.players.map(function(p){return '<div class="player"><span class="pname"><span class="dot'+(p.connected?'':' off')+'"></span>'+esc(p.name)+'</span><span>'+(p.isHost?'<span class="badge">ведущий</span> ':'')+'<span class="badge">★ '+(p.score||0)+'</span></span></div>';}).join('');
    app.innerHTML='<div class="card"><div class="big">Лобби</div><div class="status">Игроков: '+msg.players.length+'</div>'+list+'</div>'+
      '<button class="secondary" id="rulesBtn">Правила игры</button>'+
      '<div class="card hidden" id="rulesBox"><div class="rules">'+getRulesText()+'</div></div>';
    document.getElementById("rulesBtn").onclick=function(){
      var b=document.getElementById("rulesBox");
      if(b.classList.contains("hidden")){b.classList.remove("hidden");this.textContent="Скрыть правила";}
      else{b.classList.add("hidden");this.textContent="Правила игры";}
    };
  }
  function renderRole(msg){
    var isMrX=!!msg.isMrX;
    app.innerHTML='<div class="card"><div class="cat">'+esc(msg.category)+'</div><div class="word">'+(isMrX?"? Mr. X":esc(msg.word))+'</div><div class="hint">'+(isMrX?"Вы Mr. X. Слова у вас нет — вы знаете ТОЛЬКО категорию. Слушайте чужие ассоциации и блефуйте.":"Ваше слово. Не выдайте себя!")+'</div></div>';
  }
  function renderDiscussion(msg){
    var speaker=msg.players.find(function(p){return p.id===msg.currentSpeakerId;});
    var mine=msg.currentSpeakerId===me;
    var list=msg.players.map(function(p){
      var now=p.id===msg.currentSpeakerId?' style="outline:2px solid var(--accent2)"':'';
      return '<div class="player"'+now+'><span class="pname"><span class="dot'+(p.connected?'':' off')+'"></span>'+esc(p.name)+'</span>'+(p.id===me?'<span class="badge">вы</span>':'')+'</div>';
    }).join('');
    var wc='';
    if(myWord){wc='<div class="card"><div class="cat">'+esc(myCategory||'')+'</div><div class="word">'+(myIsMrX?'? Mr. X':esc(myWord))+'</div><div class="hint">'+(myIsMrX?'Слова нет — только категория. Блефуйте!':'Ваше слово')+'</div></div>';}
    else{wc='<div class="card"><div class="hint">Вы зритель этого раунда — вступите со следующего.</div></div>';}
    app.innerHTML=wc+'<div class="card"><div class="timer">'+fmt(msg.secondsLeft)+'</div><div class="big">'+(mine?'Ваш ход!':'Говорит: '+esc(speaker?speaker.name:'-'))+'</div><div class="status">Назовите ассоциацию.</div>'+list+'</div>';
  }
  function renderVoting(msg){
    // Зритель (вошёл посреди раунда): слова нет — голосовать нельзя
    if(!myWord){app.innerHTML='<div class="card"><div class="big">Идёт голосование</div><div class="status" id="vp">Вы зритель этого раунда — вступите со следующего</div></div>';return;}
    var isMrX=myIsMrX;
    var isRevote=!!msg.isRevote;
    var cands=msg.candidateIds||[];
    var pool=msg.players.filter(function(p){return p.id!==me&&(cands.length===0||cands.indexOf(p.id)!==-1);});
    var btns=pool.map(function(p){return '<button class="vote-btn" data-id="'+p.id+'">'+esc(p.name)+'</button>';}).join('');
    var title=isRevote?'Переголосование!':(isMrX?'Кто лишний?':'Кто шпион?');
    var sub=isRevote?'Голоса разделились — выберите между лидерами':'Выберите подозреваемого';
    // Догадка о слове мирных: одинаковая сетка у всех (список никого не выдаёт).
    // Mr. X видит её сразу, остальные — после голосования. Считается только догадка лишнего.
    var guessBox='';
    if(myGuessOptions.length>0){
      var opts=myGuessOptions.map(function(w){return '<button class="vote-btn mrx-opt'+(myGuess===w?' sel':'')+'" data-word="'+esc(w)+'">'+esc(w)+'</button>';}).join('');
      var head=isMrX?'Mr. X: угадайте слово мирных!':'Думаете, вы шпион? Угадайте слово мирных (по желанию):';
      guessBox='<div class="card'+(isMrX||myGuess?'':' hidden')+'" id="guessbox"><div class="status" style="color:var(--accent2);font-weight:700">'+head+'</div>'+opts+'</div>';
    }
    app.innerHTML='<div class="card"><div class="big">'+title+'</div><div class="status" id="vp">'+sub+'</div>'+btns+'</div>'+guessBox;
    Array.prototype.forEach.call(document.querySelectorAll('.vote-btn:not(.mrx-opt)'),function(b){b.onclick=function(){selectedSuspect=b.getAttribute('data-id');Array.prototype.forEach.call(document.querySelectorAll('.vote-btn:not(.mrx-opt)'),function(x){x.classList.remove('sel');});b.classList.add('sel');send({type:'Vote',suspectId:selectedSuspect});document.getElementById('vp').textContent='Голос учтён';var gb=document.getElementById('guessbox');if(gb)gb.classList.remove('hidden');};});
    Array.prototype.forEach.call(document.querySelectorAll('.mrx-opt'),function(b){b.onclick=function(){Array.prototype.forEach.call(document.querySelectorAll('.mrx-opt'),function(x){x.classList.remove('sel');});b.classList.add('sel');myGuess=b.getAttribute('data-word');send({type:'SpyGuess',word:myGuess});toast('Догадка отправлена');};});
  }
  function upVP(msg){var el=document.getElementById('vp');if(el)el.textContent='Проголосовало: '+msg.votedPlayerIds.length+'/'+msg.totalVoters;}
  function renderFinalGuess(msg){
    if(msg.accusedId===me){
      // Экран одинаков для любого обвинённого — роль не раскрывается
      var opts=myGuessOptions.map(function(w){return '<button class="vote-btn mrx-opt'+(myGuess===w?' sel':'')+'" data-word="'+esc(w)+'">'+esc(w)+'</button>';}).join('');
      app.innerHTML='<div class="card"><div class="big" style="color:var(--accent)">Вас обвинили!</div>'+
        '<div class="hint">Если вы лишний — угадайте слово мирных, это спасёт вас до ничьей. Если вы мирный — просто пропустите. У вас '+msg.seconds+' сек.<br/><b>Выбор окончателен: первое же нажатие завершает раунд.</b></div></div>'+
        (opts?'<div class="card">'+opts+'</div>':'')+
        '<button class="secondary" id="skipBtn">Я мирный — пропустить</button>';
      Array.prototype.forEach.call(document.querySelectorAll('.mrx-opt'),function(b){b.onclick=function(){myGuess=b.getAttribute('data-word');send({type:'SpyGuess',word:myGuess});};});
      document.getElementById('skipBtn').onclick=function(){send({type:'SkipGuess'});};
    }else{
      app.innerHTML='<div class="card"><div class="big">Обвинили: '+esc(msg.accusedName)+'</div>'+
        '<div class="hint">Ждём его последнего слова... Если он лишний — может угадать слово мирных и спастись до ничьей ('+msg.seconds+' сек).</div></div>';
    }
  }
  setInterval(function(){if(ws&&ws.readyState===1)send({type:"Ping"});},20000);
  function renderResult(msg){
    var youSpy=msg.spyIds.indexOf(me)!==-1;
    var roleText=youSpy?'Вы были шпионом':(myIsMrX?'Вы были Mr. X':'Вы были мирным');
    var hasSpy=msg.spyNames&&msg.spyNames.length>0;
    var spyInfo=hasSpy?'<div class="status">Шпион: <b>'+esc(msg.spyNames.join(', '))+'</b></div>':'';
    // Mr. X теперь тоже раскрывается: раньше его личность не показывалась вообще,
    // и после раунда было непонятно, кого же надо было ловить.
    var mrxInfo=msg.mrXName?'<div class="status">Mr. X: <b>'+esc(msg.mrXName)+'</b></div>':'';
    var wordsInfo='<div class="status">Слово мирных: <b>'+esc(msg.civilianWord)+'</b>'+(hasSpy?'<br/>Слово шпиона: <b>'+esc(msg.spyWord)+'</b>':'')+'</div>';
    var oc=msg.outcome||'NONE';
    var color=oc==='CIVILIANS'?'var(--ok)':(oc==='IMPOSTORS'?'var(--accent)':(oc==='DRAW'?'var(--accent2)':'var(--muted)'));
    var scores='';
    if(msg.scores&&msg.scores.length>0){
      scores='<div class="card"><div class="big">Очки партии</div>'+msg.scores.map(function(p,i){
        return '<div class="player"><span class="pname">'+(i===0?'👑 ':'')+esc(p.name)+(p.id===me?' <span class="badge">вы</span>':'')+'</span><span class="badge">★ '+(p.score||0)+'</span></div>';
      }).join('')+'</div>';
    }
    app.innerHTML='<div class="card"><div class="result" style="color:'+color+'">'+esc(msg.message)+'</div>'+spyInfo+mrxInfo+wordsInfo+(msg.accusedName?'<div class="status">Обвинили: '+esc(msg.accusedName)+'</div>':'<div class="status">Голоса разделились</div>')+'<div class="big">'+roleText+'</div></div>'+scores;
  }
  connect();
})();
</script>
</body>
</html>
""".trimIndent()
}
