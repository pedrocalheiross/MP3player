import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.UnsupportedTagException;
import javazoom.jl.decoder.*;
import javazoom.jl.player.AudioDevice;
import javazoom.jl.player.FactoryRegistry;
import support.PlayerWindow;
import support.Song;

import javax.swing.event.MouseInputAdapter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class Player {


    // Declarando:
    private PlayerWindow oi;
    private ArrayList<Song> playlist = new ArrayList<Song>();
    private ArrayList<String[]> temp = new ArrayList<String[]>();
    private String[][] s = {};

    private ArrayList<Song> shufflelist = new ArrayList<Song>();
    private ArrayList<String[]> tempshuf = new ArrayList<String[]>();

    private String[] shuffleString;
    private String[] info;

    // Variveis Booleanas
    private boolean isPlaying = false;
    private boolean isPaused = false;
    private boolean loop= false;
    private boolean flow = false;
    private boolean doublePlay=false;

    private boolean shuffle = false;

    // Variaveis Inteiras
    private int cnt = 0;
    private int actualIndex;
    private int actualTime;
    private int totalTime;


    // Variaves Song
    Song currentMusic;
    Song shuffleMusic;



    /**
     * The MPEG audio bitstream.
     */
    private Bitstream bitstream;
    /**
     * The MPEG audio decoder.
     */
    private Decoder decoder;
    /**
     * The AudioDevice where audio samples are written to.
     */
    private AudioDevice device;

    private int currentFrame = 0;


    //Area da Acao:
    private final ActionListener buttonListenerPlayNow = e -> {
        actualIndex = oi.getIndex();
        playNow(actualIndex);};
    private final ActionListener buttonListenerRemove = e -> {removing();};
    private final ActionListener buttonListenerAddSong = e -> {adding();};
    private final ActionListener buttonListenerPlayPause = e -> {playPause();};
    private final ActionListener buttonListenerStop = e -> {stop();};
    private final ActionListener buttonListenerNext = e -> {next();};
    private final ActionListener buttonListenerPrevious = e -> {previous();};
    private final ActionListener buttonListenerShuffle = e -> {shuffling();};
    private final ActionListener buttonListenerLoop = e -> {loopButton();};
    private final MouseInputAdapter scrubberMouseInputAdapter = new MouseInputAdapter() {
        @Override
        public void mouseReleased(MouseEvent e) {
            release();
        }

        @Override
        public void mousePressed(MouseEvent e) {
            isPaused = true;
        }

        @Override
        public void mouseDragged(MouseEvent e) {
        }
    };

    public Player() {
        EventQueue.invokeLater(() -> oi = new PlayerWindow(
                ("Tocador MP3"),
                s,
                buttonListenerPlayNow,
                buttonListenerRemove,
                buttonListenerAddSong,
                buttonListenerShuffle,
                buttonListenerPrevious,
                buttonListenerPlayPause,
                buttonListenerStop,
                buttonListenerNext,
                buttonListenerLoop,
                scrubberMouseInputAdapter)
        );
    }

    //<editor-fold desc="Essential">

    /**
     * @return False if there are no more frames to play.
     */
    private boolean playNextFrame() throws JavaLayerException {
        // TODO: Is this thread safe?
        if (device != null) {
            Header h = bitstream.readFrame();
            if (h == null) return false;

            SampleBuffer output = (SampleBuffer) decoder.decodeFrame(h, bitstream);
            device.write(output.getBuffer(), 0, output.getBufferLength());
            bitstream.closeFrame();
        }
        return true;
    }

    /**
     * @return False if there are no more frames to skip.
     */
    private boolean skipNextFrame() throws BitstreamException {
        // TODO: Is this thread safe?
        Header h = bitstream.readFrame();
        if (h == null) return false;
        bitstream.closeFrame();
        currentFrame++;
        return true;
    }

    /**
     * Skips bitstream to the target frame if the new frame is higher than the current one.
     *
     * @param newFrame Frame to skip to.
     * @throws BitstreamException Generic Bitstream exception.
     */
    private void skipToFrame(int newFrame) throws BitstreamException {
        // TODO: Is this thread safe?
        if (newFrame > currentFrame) {
            int framesToSkip = newFrame - currentFrame;
            boolean condition = true;
            while (framesToSkip-- > 0 && condition) condition = skipNextFrame();
        }
    }


    // Funcoes do Mouse:

    // Release:

    public void release(){

        try {

             // recria o device, decoder e bitstream

            currentFrame = 0;
            device = FactoryRegistry.systemRegistry().createAudioDevice();
            device.open(decoder = new Decoder());
            bitstream = new Bitstream(currentMusic.getBufferedInputStream());

        }
        catch (JavaLayerException | FileNotFoundException ex){
            throw new RuntimeException(ex);
        }

        // pega o valor de onde parou no scrubber
        int skipTo;
        skipTo = (int) (oi.getScrubberValue() / currentMusic.getMsPerFrame());

        cnt = skipTo;

        oi.setTime((int) (cnt * currentMusic.getMsPerFrame()), totalTime);

        // pula
        try {
            skipToFrame(skipTo);

        } catch (BitstreamException ex){
            throw new RuntimeException(ex);
        }

        if(isPlaying){
            isPaused = false;
        }

        playing();

    }

    // Funcoes dos Botoes:



    // Next:
    public void next() {
        if(actualIndex != playlist.size()-1){
            actualIndex++;
            playNow(actualIndex);
        }
        else if(actualIndex == playlist.size()-1 && loop){
            actualIndex = 0;
            playNow(actualIndex);
        }
    }

    // Previous:

    public void previous(){
        if(actualIndex != 0){
            actualIndex--;
            playNow(actualIndex);
        }
    }


    // Loop (Prefire)
    public void loopButton(){
        loop = !loop;
    }

    // Atualiza a QueueList
    public void queueAtt(){
        String[][] converter = new String[this.temp.size()][7]; // Cria uma matriz, as linhas sao musicas e as 7 colunas são as categorias da musica
        this.s = this.temp.toArray(converter); //Converter o array list em uma matriz de array e atualizar a fila
        oi.setQueueList(this.s); // Atualiza a interface
    }

    // Funcao do PlayNow
    public void playNow(int idx) {
        cnt = 0;
        currentFrame = 0;

        if(isPlaying){
            doublePlay = true; // Encerra a música anterior
        }

        Thread playn = new Thread(() -> {

            isPlaying = true;
            isPaused = false;

            currentMusic = playlist.get(idx); // Pega a musica selecionada na playlist

            //Atualiza as informações no player e libera os botoes nescessarios
            oi.setPlayingSongInfo(currentMusic.getTitle(), currentMusic.getAlbum(), currentMusic.getArtist());
            oi.setPlayPauseButtonIcon(isPlaying);
            oi.setEnabledPlayPauseButton(isPlaying);
            oi.setEnabledStopButton(isPlaying);
            oi.setEnabledScrubber(isPlaying);


            // Processo presente no GoogleClassroom
            try {
                /*
                 * Cria o device, decoder e bitstream para rodar a música e chama função
                 * playing, que toca a música de fato
                 */
                device = FactoryRegistry.systemRegistry().createAudioDevice();
                device.open(decoder = new Decoder());
                bitstream = new Bitstream(currentMusic.getBufferedInputStream());
                playing();

            }
            catch (JavaLayerException | FileNotFoundException ex){
                throw new RuntimeException(ex);
            }
        });
        playn.start();

        }

    // Funcao responsavel por tocar as musicas em si
    public void playing(){
        Thread running = new Thread(() -> {
            flow = true;

            while (flow && !isPaused) {

                try {
                    if(doublePlay){
                        doublePlay = false; // Para evitar sobreposição de músicas
                        break;
                    }

                    oi.setEnabledNextButton(actualIndex != playlist.size() - 1);
                    oi.setEnabledPreviousButton(actualIndex != 0);


                    actualTime = (int) (cnt * currentMusic.getMsPerFrame());
                    totalTime = (int) currentMusic.getMsLength();
                    oi.setTime(actualTime, totalTime); //Atualizar o tempo do scrubber
                    if(oi.getScrubberValue() < currentMusic.getMsLength()){
                        flow = playNextFrame();} //Toca o próximo frame da música
                    else{
                        flow = false;
                    }
                    cnt++;
                } catch (JavaLayerException ex) {
                    throw new RuntimeException(ex);
                }
            }
            if(!flow) {
                isPlaying = false;
            }
            if(!flow && actualIndex < playlist.size() - 1){
                next(); // comeca a proxima música da lista
            }
            else if(!flow && actualIndex == playlist.size() - 1 && !loop){
                stop(); // se acabar as musicas e nao tiver em looop para;
            }
            else if(!flow && actualIndex == playlist.size() - 1 && loop){
                actualIndex =0;//se acabar as musicas e tiver em looop volta pro comeco
                playNow(actualIndex);
            }

        });
        running.start();
    }

    // Funcao do Stop
    public void stop(){

        cnt = 0;
        isPlaying = false;
        isPaused = true;
        oi.resetMiniPlayer();

    }

    // Funcao Play Pause
    public void playPause() {

        // atualiza as variaveis
        isPlaying = isPaused;
        isPaused = !isPaused;


        oi.setPlayPauseButtonIcon(isPlaying);// Muda o botao entre Play e Pause

        // faz tocar :)
        if(!isPaused) {
            playing();
        }
    }

    // Shuffle:
    public void shuffling(){
        Thread shuf = new Thread(() -> {

            shuffle = !shuffle;

            // se ja tiver tocando, a musica nao vai ser inclusa na mistura
            if(isPlaying || isPaused) {
                if (shuffle) {

                    // armazena a ordem original pra poder pegar no futuro
                    tempshuf.clear();
                    shufflelist.clear();
                    shufflelist.addAll(playlist);
                    tempshuf.addAll(temp);

                    //troca a musica que ta tocando pra posicao 0
                    shuffleMusic = playlist.get(actualIndex);
                    playlist.set(actualIndex, playlist.get(0));
                    playlist.set(0, shuffleMusic);
                    shuffleString = temp.get(actualIndex);
                    temp.set(actualIndex, temp.get(0));
                    temp.set(0, shuffleString);

                    actualIndex = 0;


                    // percorre a lista aleatorizando, mas sem pegar a musica que ta tocando
                    for (int i = 1; i < playlist.size(); i++) {
                        int randomNumber = (int) Math.floor(Math.random() * (playlist.size() - i) + i);
                        shuffleMusic = playlist.get(randomNumber);
                        playlist.set(randomNumber, playlist.get(i));
                        playlist.set(i, shuffleMusic);

                        shuffleString = temp.get(randomNumber);
                        temp.set(randomNumber, temp.get(i));
                        temp.set(i, shuffleString);
                    }

                    //atualizamos a interface
                    queueAtt();



                } else {
                    //volta pra ordem original

                    //poe de volta a ordem original que tinha sido armazenada
                    playlist.clear();
                    temp.clear();
                    playlist.addAll(shufflelist);
                    temp.addAll(tempshuf);

                    for (int i = 0; i < playlist.size(); i++) {
                        if (currentMusic == playlist.get(i)) {
                            actualIndex = i; //Atualiza o actualIndex
                            break;
                        }
                    }
                    queueAtt(); //Atualiza a lista na tela
                }
            }



            else{
                // como nao estava com musica tocando, mistura tudo

                if (shuffle) {

                    // salva a ordem original
                    tempshuf.clear();
                    shufflelist.clear();
                    shufflelist.addAll(playlist);
                    tempshuf.addAll(temp);



                    // aleatoriza tudo dessa vez, percorrendo a lista toda
                    for (int i = 0; i < playlist.size(); i++) {
                        int randomNumber = (int) Math.floor(Math.random() * (playlist.size() - i) + i);
                        shuffleMusic = playlist.get(randomNumber);
                        playlist.set(randomNumber, playlist.get(i));
                        playlist.set(i, shuffleMusic);

                        shuffleString = temp.get(randomNumber);
                        temp.set(randomNumber, temp.get(i));
                        temp.set(i, shuffleString);
                    }

                    queueAtt();//atualiza a interface

                    
                } else {

                    // poe de volta a ordem original
                    playlist.clear();
                    temp.clear();
                    playlist.addAll(shufflelist);
                    temp.addAll(tempshuf);

                    for (int i = 0; i < playlist.size(); i++) {
                        if (currentMusic == playlist.get(i)) {
                            actualIndex = i;
                            break;
                        }
                    }
                    queueAtt();//atualiza a interface
                }

            }

        });
        shuf.start();

    }

    //Funcao de adicionar musica na playlist
    public void adding(){

        Thread addThread = new Thread(() -> {

            try {

                // Seleciona a musica e adiciona na playlist
                Song music = this.oi.openFileChooser();
                info = music.getDisplayInfo();
                temp.add(info);
                playlist.add(music);
                tempshuf.add(info);
                shufflelist.add(music);

                queueAtt();// Funcao de atualizar queue

                //atualiza os botoes de shuffle e loop
                oi.setEnabledLoopButton(playlist.size()!=0);
                oi.setEnabledShuffleButton(playlist.size()>1);

            } catch (IOException | InvalidDataException | BitstreamException | UnsupportedTagException ex) {
                throw new RuntimeException(ex);
            }
        });
        addThread.start();
    }

    // Funcao de remover musica na playlist
    public void removing(){

        Thread removeThread = new Thread(() -> {

            // Pega o index da musica que sera removida
            int removeIndex;

            removeIndex = oi.getIndex();

            // Remove a mesma
            Song rmusic = playlist.get(removeIndex);
            temp.remove(removeIndex);
            playlist.remove(removeIndex);

            for (int i = 0; i < playlist.size(); i++) {
                if (rmusic == shufflelist.get(i)) {
                    shufflelist.remove(i);
                    tempshuf.remove(i);
                    break;
                }
            }

            // Se a musica removida tiver tocando vai pra prox a nao ser que seja a ultima
            if(removeIndex == actualIndex && isPlaying){
                if(actualIndex != playlist.size()){
                    playNow(actualIndex);
                }
                else{
                    stop();
                }
            }
            else if(removeIndex < actualIndex){
                actualIndex--;
            }

            queueAtt();// Atualiza a queue

            //atualiza os botoes de shuffle e loop
            oi.setEnabledLoopButton(playlist.size()!=0);
            oi.setEnabledShuffleButton(playlist.size()>1);

        });
        removeThread.start();

    }


}
