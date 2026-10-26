package cn.visualassistant.app.spatial

import android.content.Context
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.graphics.PixelFormat
import android.view.ScaleGestureDetector
import cn.visualassistant.app.online3d.RemoteColoredMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/** A real depth-buffered viewer. Compose Canvas cannot correctly rasterise an intersecting 3-D mesh. */
class ColoredMeshSurface(context:Context):GLSurfaceView(context){
    private val meshRenderer=MeshRenderer()
    private var lastX=0f;private var lastY=0f
    private val scaler=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(detector:ScaleGestureDetector):Boolean{meshRenderer.zoom=(meshRenderer.zoom*detector.scaleFactor).coerceIn(.5f,4f);requestRender();return true}
    })
    init{
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8,8,8,8,16,0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderMediaOverlay(true)
        setRenderer(meshRenderer)
        renderMode=RENDERMODE_WHEN_DIRTY
        contentDescription="家庭彩色三维模型，可单指旋转、双指缩放"
    }
    fun setMesh(mesh:RemoteColoredMesh){meshRenderer.setMesh(mesh);requestRender()}
    fun setPlaces(places:List<HomePlace>){meshRenderer.setPlaces(places);contentDescription="家庭彩色三维模型，包含${places.size}个用户标记地点，可单指旋转、双指缩放";requestRender()}
    override fun onTouchEvent(event:MotionEvent):Boolean{if(event.actionMasked==MotionEvent.ACTION_DOWN)parent?.requestDisallowInterceptTouchEvent(true);if(event.actionMasked==MotionEvent.ACTION_UP||event.actionMasked==MotionEvent.ACTION_CANCEL)parent?.requestDisallowInterceptTouchEvent(false);scaler.onTouchEvent(event);when(event.actionMasked){MotionEvent.ACTION_DOWN->{lastX=event.x;lastY=event.y}MotionEvent.ACTION_MOVE->{if(!scaler.isInProgress){meshRenderer.yaw+=(event.x-lastX)*.35f;meshRenderer.pitch=(meshRenderer.pitch+(event.y-lastY)*.25f).coerceIn(-85f,85f);requestRender()};lastX=event.x;lastY=event.y}};return true}
}

private class MeshRenderer:GLSurfaceView.Renderer{
    @Volatile private var pending:RemoteColoredMesh?=null
    @Volatile private var pendingPlaces:List<HomePlace>?=null
    private var revision=Long.MIN_VALUE;private var vertices:FloatBuffer?=null;private var colors:FloatBuffer?=null;private var count=0;private var program=0
    private var markerVertices:FloatBuffer?=null;private var markerColors:FloatBuffer?=null;private var places:List<HomePlace> = emptyList()
    private var centerX=0f;private var centerY=0f;private var centerZ=0f;private var modelScale=1f
    // ARCore world Y is gravity-aligned up. Start from a calm architectural view:
    // forward-facing, slightly elevated, rather than an arbitrary tilted orbit.
    @Volatile var yaw=0f;@Volatile var pitch=30f;@Volatile var zoom=1f
    fun setMesh(mesh:RemoteColoredMesh){if(mesh.revision!=revision)pending=mesh}
    fun setPlaces(value:List<HomePlace>){pendingPlaces=value.toList()}
    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?){glClearColor(0f,0f,0f,.58f);glEnable(GL_DEPTH_TEST);glDepthFunc(GL_LEQUAL);program=link("attribute vec3 p;attribute vec3 c;uniform mat4 m;varying vec3 color;void main(){gl_Position=m*vec4(p,1.0);gl_PointSize=18.0;color=c;}","precision mediump float;varying vec3 color;void main(){gl_FragColor=vec4(color,1.0);}")}
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int){glViewport(0,0,w,h)}
    override fun onDrawFrame(gl:GL10?){pending?.let{load(it);pending=null};pendingPlaces?.let{places=it;buildMarkers();pendingPlaces=null};glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT);val vb=vertices?:return;val cb=colors?:return
        val rotation=FloatArray(16);Matrix.setIdentityM(rotation,0);Matrix.rotateM(rotation,0,pitch,1f,0f,0f);Matrix.rotateM(rotation,0,yaw,0f,1f,0f);Matrix.scaleM(rotation,0,zoom,zoom,zoom)
        glUseProgram(program);glUniformMatrix4fv(glGetUniformLocation(program,"m"),1,false,rotation,0)
        val pa=glGetAttribLocation(program,"p");val ca=glGetAttribLocation(program,"c");vb.position(0);cb.position(0);glEnableVertexAttribArray(pa);glVertexAttribPointer(pa,3,GL_FLOAT,false,0,vb);glEnableVertexAttribArray(ca);glVertexAttribPointer(ca,3,GL_FLOAT,false,0,cb);glDrawArrays(GL_TRIANGLES,0,count)
        val mv=markerVertices;val mc=markerColors;if(mv!=null&&mc!=null){glDisable(GL_DEPTH_TEST);mv.position(0);mc.position(0);glVertexAttribPointer(pa,3,GL_FLOAT,false,0,mv);glVertexAttribPointer(ca,3,GL_FLOAT,false,0,mc);for(i in places.indices){glDrawArrays(GL_LINES,i*5,4);glDrawArrays(GL_POINTS,i*5+4,1)};glEnable(GL_DEPTH_TEST)}
        glDisableVertexAttribArray(pa);glDisableVertexAttribArray(ca)
    }
    private fun load(mesh:RemoteColoredMesh){val points=mesh.triangles.flatMap{listOf(it.a,it.b,it.c)};if(points.isEmpty())return
        val minX=points.minOf{it.x};val maxX=points.maxOf{it.x};val minY=points.minOf{it.y};val maxY=points.maxOf{it.y};val minZ=points.minOf{it.z};val maxZ=points.maxOf{it.z};centerX=(minX+maxX)/2;centerY=(minY+maxY)/2;centerZ=(minZ+maxZ)/2;modelScale=.88f/max(max(maxX-minX,maxY-minY),maxZ-minZ).coerceAtLeast(.2f)
        val v=FloatArray(points.size*3);val c=FloatArray(points.size*3);var i=0
        mesh.triangles.forEach{t->val ps=listOf(t.a,t.b,t.c);val cs=arrayOf(intArrayOf(t.ar,t.ag,t.ab),intArrayOf(t.br,t.bg,t.bb),intArrayOf(t.cr,t.cg,t.cb));for(k in 0..2){v[i*3]=(ps[k].x-centerX)*modelScale;v[i*3+1]=(ps[k].y-centerY)*modelScale;v[i*3+2]=(ps[k].z-centerZ)*modelScale;c[i*3]=cs[k][0]/255f;c[i*3+1]=cs[k][1]/255f;c[i*3+2]=cs[k][2]/255f;i++}}
        vertices=buffer(v);colors=buffer(c);count=points.size;revision=mesh.revision;buildMarkers()
    }
    private fun buildMarkers(){if(revision==Long.MIN_VALUE||places.isEmpty()){markerVertices=null;markerColors=null;return};val v=FloatArray(places.size*15);val c=FloatArray(places.size*15);places.forEachIndexed{i,p->val base=i*15;val x=(p.x-centerX)*modelScale;val y=(p.y-centerY)*modelScale;val z=(p.z-centerZ)*modelScale;val top=y+.35f*modelScale;val dx=p.forwardX*.32f*modelScale;val dz=p.forwardZ*.32f*modelScale;val points=floatArrayOf(x,y,z,x,top,z,x,y,z,x+dx,y,z+dz,x,top,z);points.copyInto(v,base);val color=PALETTE[i%PALETTE.size];for(k in 0 until 5){c[base+k*3]=color[0];c[base+k*3+1]=color[1];c[base+k*3+2]=color[2]}};markerVertices=buffer(v);markerColors=buffer(c)}
    private fun buffer(a:FloatArray)=ByteBuffer.allocateDirect(a.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply{put(a);position(0)}
    private fun shader(type:Int,source:String)=glCreateShader(type).also{glShaderSource(it,source);glCompileShader(it);val ok=IntArray(1);glGetShaderiv(it,GL_COMPILE_STATUS,ok,0);require(ok[0]!=0){glGetShaderInfoLog(it)}}
    private fun link(vertex:String,fragment:String)=glCreateProgram().also{glAttachShader(it,shader(GL_VERTEX_SHADER,vertex));glAttachShader(it,shader(GL_FRAGMENT_SHADER,fragment));glLinkProgram(it);val ok=IntArray(1);glGetProgramiv(it,GL_LINK_STATUS,ok,0);require(ok[0]!=0){glGetProgramInfoLog(it)}}
    companion object{val PALETTE=arrayOf(floatArrayOf(1f,.25f,.22f),floatArrayOf(.2f,.75f,1f),floatArrayOf(1f,.75f,.1f),floatArrayOf(.55f,1f,.35f),floatArrayOf(.8f,.4f,1f))}
}
