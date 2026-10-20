// Inserted inside the original closure in generated Android assets only.
let androidState=null;
function androidSend(message){
 if(window.NativeFuel)window.NativeFuel.postMessage(JSON.stringify(message));
}
function androidSaved(){ androidState=null;androidSend({type:'fuel',fuel:data}); }
function androidProjection(base){
 if(!window.NativeFuel)return base;
 if(!base.ready || !androidState)return base.ready?{...base,remaining:NaN,safeKm:NaN,uncertain:true}:base;
 if(androidState.events!==JSON.stringify(data.events))return {...base,remaining:NaN,safeKm:NaN,uncertain:true};
 const km=Number(androidState.km);
 if(!Number.isFinite(km)||km<0)return {...base,remaining:NaN,safeKm:NaN,uncertain:true};
 let projected=base;
 if(km>0){
  const original=data;
  try{
   data={...data,events:[...data.events,{kind:'mileage',odometer:base.odo+km,at:androidState.at}]};
   projected=replay();
  }finally{data=original}
 }
 if(androidState.unknown)return {...projected,remaining:NaN,safeKm:NaN,uncertain:true};
 return projected;
}
window.androidReceive=function(state){androidState=state;render();
 if(replay().ready && (state.unknown || state.km>0)){
  $('updatedCaption').textContent=state.unknown?'自动里程不完整或行程待确认 · 请校准 ODO':`GPS 预测 ${fmt(replay().odo+state.km,1)} km · ${dateLabel(state.at)}`;
  if(state.unknown){$('statusBanner').textContent='自动里程不可靠，剩余油量和续航暂停显示。请确认行程或输入真实 ODO。';$('statusBanner').className='status-banner show red'}
 }
};
window.androidCalibrate=function(odo){
 const c=replay();if(!c.ready||!isOdo(odo)||odo<c.odo)return androidSend({type:'error',message:'ODO 小于已确认里程，未修改数据'});
 data.events.push({kind:'mileage',odometer:odo,at:new Date().toISOString()});save();render();
};
window.androidImport=function(obj){
 if(!isDataValid(obj))return androidSend({type:'error',message:'备份格式无效，未修改原数据'});
 androidSend({type:'beforeImport'});data=normalize(obj);save();render();close();
};
document.querySelector('.brand-mark').addEventListener('click',()=>androidSend({type:'controls'}));
